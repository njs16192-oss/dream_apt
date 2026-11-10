package com.dreamapt.collect.batch;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;

import com.dreamapt.collect.ApiRequestThrottle;
import com.dreamapt.geocode.ComplexGeocodeService;
import com.dreamapt.newhigh.NewHighDetectService;
import com.dreamapt.region.RegionRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * 수집 Job 구성 (ADR 006). collectTradeJob = 구×월을 하나씩 처리하는 수집 step → 신규 단지 지오코딩 step
 * → 신고가 판단 step (ADR 011 ⑤).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class CollectBatchConfig {

    static final String COLLECT_STEP = "collectTargetsStep";
    static final String DETECT_NEW_HIGHS_STEP = "detectNewHighsStep";

    /** 업무 기준일용 시계. k8s 노드(UTC)와 관계없이 서울 날짜를 쓴다 */
    @Bean
    Clock clock(CollectBatchProperties properties) {
        return Clock.system(properties.zone());
    }

    @Bean
    RetryTemplate collectRetryTemplate(CollectBatchProperties properties) {
        CollectBatchProperties.Retry retry = properties.retry();
        RetryTemplate template = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(retry.maxRetries())
                .delay(retry.delay())
                .multiplier(retry.multiplier())
                .predicate(CollectTargetRunner::isRetryable)
                .build());
        template.setRetryListener(new RetryListener() {
            @Override
            public void onRetryFailure(RetryPolicy retryPolicy, Retryable<?> retryable, Throwable throwable) {
                log.warn("일시 오류, 백오프 후 재시도: {}", throwable.getMessage());
            }
        });
        return template;
    }

    @Bean
    Job collectTradeJob(JobRepository jobRepository, Step collectTargetsStep, Step geocodeComplexesStep,
            Step detectNewHighsStep, RunningCollectJobs runningJobs) {
        return new JobBuilder(RunningCollectJobs.JOB_NAME, jobRepository)
                .validator(CollectJobParameters.validator())
                .listener(new SingleRunningModeListener(runningJobs))
                // 마지막 step(신고가 판단)이 아니라 수집 step의 결과로 종료 코드를 정한다
                .listener(new CollectStepExitStatusListener(COLLECT_STEP))
                .start(collectTargetsStep)
                // 수집 step이 FAILED면 Job이 멈추고 뒤 step은 돌지 않는다
                .next(geocodeComplexesStep)
                .next(detectNewHighsStep)
                .build();
    }

    /**
     * API 호출 중 DB 커넥션을 잡지 않도록 step 트랜잭션은 ResourcelessTransactionManager(아무것도 하지 않음)로 감싼다.
     * 거래 저장 트랜잭션은 TradeReconcileService가 연다.
     */
    @Bean
    Step collectTargetsStep(JobRepository jobRepository, CollectTargetsTasklet collectTargetsTasklet) {
        return new StepBuilder(COLLECT_STEP, jobRepository)
                .tasklet(collectTargetsTasklet, new ResourcelessTransactionManager())
                .listener(collectTargetsTasklet)
                .build();
    }

    /**
     * 좌표 없는 신규 단지 지오코딩 (ADR 010). 수집 step과 같은 이유로 ResourcelessTransactionManager를 쓴다.
     * 기본 트랜잭션 매니저면 tasklet 전체(약 2분)가 트랜잭션 하나로 묶여 단지마다 바로 커밋하는 설계가 깨지고,
     * 중간 예외에 앞서 저장한 좌표까지 롤백된다.
     */
    @Bean
    Step geocodeComplexesStep(JobRepository jobRepository, ComplexGeocodeService geocodeService,
            CollectBatchProperties properties) {
        return new StepBuilder("geocodeComplexesStep", jobRepository)
                .tasklet(new GeocodeComplexesTasklet(geocodeService, properties.geocodeLimit()),
                        new ResourcelessTransactionManager())
                .build();
    }

    /**
     * 신고가 판단 (ADR 011 ⑤). 트랜잭션은 NewHighDetectService가 연다 (PR 7에서 알림 생성이 같은 트랜잭션에 들어간다).
     * step을 기본 트랜잭션 매니저로 감싸면 서비스 트랜잭션이 step 트랜잭션에 합류한다. 그러면 서비스 예외가 step 트랜잭션을
     * 롤백 전용으로 만들어, tasklet이 예외를 잡아도 step 커밋이 실패하고 Job이 FAILED(종료 코드 2)가 된다.
     * 그래서 다른 step처럼 ResourcelessTransactionManager를 쓴다.
     */
    @Bean
    Step detectNewHighsStep(JobRepository jobRepository, DetectNewHighsTasklet detectNewHighsTasklet) {
        return new StepBuilder(DETECT_NEW_HIGHS_STEP, jobRepository)
                .tasklet(detectNewHighsTasklet, new ResourcelessTransactionManager())
                .build();
    }

    @Bean
    @StepScope
    DetectNewHighsTasklet detectNewHighsTasklet(
            @Value("#{jobParameters['" + CollectJobParameters.BASE_DATE + "']}") String baseDate,
            NewHighDetectService detectService,
            Clock clock) {
        return new DetectNewHighsTasklet(detectService, baseDate == null ? null : LocalDate.parse(baseDate), clock);
    }

    @Bean
    @StepScope
    CollectTargetsTasklet collectTargetsTasklet(
            @Value("#{jobParameters['" + CollectJobParameters.MODE + "']}") String mode,
            @Value("#{jobParameters['" + CollectJobParameters.BASE_DATE + "']}") String baseDate,
            RegionRepository regionRepository,
            CollectHistoryRepository historyRepository,
            CollectTargetRunner runner,
            ApiRequestThrottle throttle,
            CollectBatchProperties properties,
            Clock clock) {
        return new CollectTargetsTasklet(CollectMode.from(mode),
                baseDate == null ? null : LocalDate.parse(baseDate),
                regionRepository, historyRepository, runner, throttle, properties, clock);
    }
}
