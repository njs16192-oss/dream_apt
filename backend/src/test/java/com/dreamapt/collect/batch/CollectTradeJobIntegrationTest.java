package com.dreamapt.collect.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dreamapt.collect.ApiRequestThrottle;
import com.dreamapt.collect.FetchedTrades;
import com.dreamapt.collect.TradeApiClient;
import com.dreamapt.collect.TradeApiException;
import com.dreamapt.collect.TradeApiException.Kind;
import com.dreamapt.geocode.ComplexGeocodeService;
import com.dreamapt.geocode.GeocodeResult;
import com.dreamapt.geocode.JusoApiException;
import com.dreamapt.support.TestcontainersConfiguration;

/**
 * collectTradeJob을 실제 DB(메타데이터·collect_history)로 실행한다. API만 가짜로 바꾼다.
 * backfill 시작을 2026-09로 줄여 대상은 2개월 × 25개 구 = 50개다.
 */
@SpringBootTest(properties = {
        "dreamapt.collect.batch.backfill-start=2026-09",
        "dreamapt.collect.batch.backfill-max-calls=30",
        "dreamapt.collect.batch.retry.delay=1ms",
        "dreamapt.data-go-kr.request-interval=0ms",
})
@Import(TestcontainersConfiguration.class)
class CollectTradeJobIntegrationTest {

    private static final LocalDate BASE_DATE = LocalDate.of(2026, 10, 25);
    private static final AtomicLong RUN_ID = new AtomicLong();

    @Autowired
    JobOperator jobOperator;

    @Autowired
    Job collectTradeJob;

    @Autowired
    JobRepository jobRepository;

    @Autowired
    ApiRequestThrottle throttle;

    @Autowired
    CollectJobLauncher launcher;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    TradeApiClient apiClient;

    // 지오코딩 step이 부르는 서비스. 기본은 키가 있고 새 단지 3개를 처리한 결과
    @MockitoBean
    ComplexGeocodeService geocodeService;

    /** 구×월(예: "11500×202609")별 실패. 없으면 빈 응답으로 성공 */
    private final Map<String, RuntimeException> failures = new HashMap<>();

    @BeforeEach
    void setUp() {
        for (String table : List.of("batch_step_execution_context", "batch_step_execution",
                "batch_job_execution_context", "batch_job_execution_params", "batch_job_execution",
                "batch_job_instance", "collect_history")) {
            jdbc.update("DELETE FROM " + table);
        }
        given(apiClient.fetch(anyString(), anyString())).willAnswer(inv -> {
            throttle.acquire();
            RuntimeException failure = failures.get(inv.getArgument(0) + "×" + inv.getArgument(1));
            if (failure != null) {
                throw failure;
            }
            return new FetchedTrades(List.of(), 0);
        });
        given(geocodeService.ready()).willReturn(true);
        given(geocodeService.geocode(anyInt(), anyBoolean()))
                .willReturn(new GeocodeResult(3, 2, 1, 0, 0, 0, 0, false, 0));
    }

    @Test
    void 수집이_일부_실패하면_지오코딩이_성공해도_종료_코드는_3이다() {
        failures.put("11500×202609", new TradeApiException("원본 불완전"));

        JobExecution execution = run(CollectMode.DAILY);

        // 마지막 step(지오코딩)은 COMPLETED지만 Job은 수집 step의 결과를 따른다
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(stepExitCode(execution, "geocodeComplexesStep")).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(CollectExitStatus.COMPLETED_WITH_FAILURES);
        assertThat(CollectJobExitCodes.of(execution)).isEqualTo(3);
        verify(geocodeService).geocode(200, false);
    }

    @Test
    void 수집_뒤_지오코딩은_limit만큼_실패한_단지는_빼고_한_번_돌고_결과를_남긴다() {
        JobExecution execution = run(CollectMode.DAILY);

        assertThat(CollectJobExitCodes.of(execution)).isZero();
        verify(geocodeService, times(1)).geocode(200, false);
        assertThat(summary(execution, "geocodeComplexesStep"))
                .containsEntry("summary.attempted", 3)
                .containsEntry("summary.succeeded", 2)
                .containsEntry("summary.failed", 1)
                .containsEntry("summary.remaining", 0L);
    }

    @Test
    void 지오코딩이_실패해도_수집_결과와_종료_코드는_그대로다() {
        given(geocodeService.geocode(anyInt(), anyBoolean()))
                .willThrow(new JusoApiException(JusoApiException.Kind.AUTH, "E0001"));

        JobExecution execution = run(CollectMode.DAILY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(CollectJobExitCodes.of(execution)).isZero();
        assertThat(summary(execution, "geocodeComplexesStep")).containsEntry("summary.error", "E0001");
    }

    @Test
    void 키가_없으면_지오코딩을_건너뛴다() {
        given(geocodeService.ready()).willReturn(false);

        JobExecution execution = run(CollectMode.DAILY);

        assertThat(CollectJobExitCodes.of(execution)).isZero();
        verify(geocodeService, never()).geocode(anyInt(), anyBoolean());
        assertThat(summary(execution, "geocodeComplexesStep")).containsEntry("summary.skipped", "no-keys");
    }

    @Test
    void 일일_수집은_150개_구월을_수집하고_결과를_남긴다() {
        JobExecution execution = run(CollectMode.DAILY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.COMPLETED.getExitCode());
        assertThat(CollectJobExitCodes.of(execution)).isZero();
        assertThat(count("status = 'SUCCESS'")).isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT job_execution_id) FROM collect_history", Long.class))
                .isEqualTo(1);
    }

    @Test
    void 구월_하나가_실패해도_나머지를_수집하고_COMPLETED_WITH_FAILURES로_끝난다() {
        failures.put("11500×202609", new TradeApiException("원본 불완전"));
        // 일시 오류는 재시도를 다 써도 그 구×월만 실패
        failures.put("11680×202607", new TradeApiException(Kind.TRANSIENT, "503"));

        JobExecution execution = run(CollectMode.DAILY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(CollectExitStatus.COMPLETED_WITH_FAILURES);
        assertThat(CollectJobExitCodes.of(execution)).isEqualTo(3);
        assertThat(count("status = 'SUCCESS'")).isEqualTo(148);
        assertThat(jdbc.queryForList("SELECT sgg_cd || '×' || deal_ym || ' ' || failure_reason || ' calls=' || api_calls"
                + " FROM collect_history WHERE status = 'FAILED' ORDER BY 1", String.class))
                .containsExactly("11500×202609 OTHER: 원본 불완전 calls=1",
                        "11680×202607 TRANSIENT: 503 calls=4");
    }

    @Test
    void 한도를_넘으면_남은_구월을_처리하지_않고_FAILED로_끝난다() {
        // 첫 달(2026-10)의 다섯 번째 구에서 한도 초과
        failures.put("11215×202610", new TradeApiException(Kind.QUOTA_EXCEEDED, "한도 초과"));

        JobExecution execution = run(CollectMode.DAILY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(CollectJobExitCodes.of(execution)).isEqualTo(2);
        assertThat(count("true")).isEqualTo(5);
        assertThat(count("status = 'FAILED'")).isEqualTo(1);
        // 수집 step이 FAILED면 지오코딩 step까지 가지 않는다
        verify(geocodeService, never()).geocode(anyInt(), anyBoolean());
    }

    @Test
    void backfill은_호출_상한까지_수집하고_다음_실행이_이어서_한다() {
        failures.put("11110×202609", new TradeApiException("원본 불완전"));

        // 1차: 2026-10의 25개 구, 2026-09의 5개 구까지 30개. 11110×202609(26번째)는 실패
        JobExecution first = run(CollectMode.BACKFILL);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 상한에 걸려 멈춘 것은 의도한 동작이라 0 (1차에서 실패한 구×월이 있어 여기서는 3)
        assertThat(CollectJobExitCodes.of(first)).isEqualTo(3);
        assertThat(count("true")).isEqualTo(30);
        assertThat(count("status = 'SUCCESS'")).isEqualTo(29);
        assertThat(summary(first)).containsEntry("summary.remaining", 20).containsEntry("summary.callLimitReached", true);

        // 2차: 성공한 29개는 건너뛰고, 남은 20개 + 실패한 1개를 다시 시도 (여전히 실패)
        JobExecution second = run(CollectMode.BACKFILL);
        assertThat(summary(second)).containsEntry("summary.skipped", 29).containsEntry("summary.targets", 21)
                .containsEntry("summary.remaining", 0);
        assertThat(second.getExitStatus().getExitCode()).isEqualTo(CollectExitStatus.COMPLETED_WITH_FAILURES);
        assertThat(count("status = 'SUCCESS'")).isEqualTo(49);

        // 3차: 실패한 구×월만 다시 시도해서 성공
        failures.clear();
        JobExecution third = run(CollectMode.BACKFILL);
        assertThat(summary(third)).containsEntry("summary.targets", 1).containsEntry("summary.succeeded", 1);
        assertThat(count("status = 'SUCCESS'")).isEqualTo(50);
    }

    @Test
    void 같은_모드가_실행_중이면_시작하지_않고_STOPPED_ALREADY_RUNNING으로_끝난다() {
        fakeRunning(CollectMode.DAILY, Duration.ofMinutes(10));

        JobExecution execution = run(CollectMode.DAILY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.STOPPED);
        assertThat(execution.getExitStatus().getExitCode()).isEqualTo(CollectExitStatus.ALREADY_RUNNING);
        assertThat(CollectJobExitCodes.of(execution)).isEqualTo(4);
        assertThat(execution.getStepExecutions()).isEmpty();
        assertThat(count("true")).isZero();
        verify(geocodeService, never()).geocode(anyInt(), anyBoolean());
    }

    @Test
    void 다른_모드가_실행_중이면_그대로_실행한다() {
        fakeRunning(CollectMode.BACKFILL, Duration.ofMinutes(10));

        JobExecution execution = run(CollectMode.WEEKLY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("true")).isEqualTo(150);
    }

    @Test
    void 오래된_STARTED_기록이_있어도_새_실행이_시작된다() {
        fakeRunning(CollectMode.DAILY, Duration.ofHours(3));

        JobExecution execution = run(CollectMode.DAILY);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("true")).isEqualTo(150);
    }

    @Test
    void backfill이_실패_없이_상한에_걸려_멈추면_종료_코드는_0이다() {
        JobExecution execution = run(CollectMode.BACKFILL);

        assertThat(summary(execution)).containsEntry("summary.callLimitReached", true);
        assertThat(CollectJobExitCodes.of(execution)).isZero();
    }

    @Test
    void 같은_날_같은_모드를_다시_실행할_수_있다() {
        LocalDate today = LocalDate.of(2026, 10, 25);

        JobExecution first = launcher.run(CollectMode.WEEKLY, today);
        JobExecution second = launcher.run(CollectMode.WEEKLY, today);

        assertThat(first.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(second.getJobInstance().getId()).isNotEqualTo(first.getJobInstance().getId());
    }

    @Test
    void 관리_API용_비동기_실행은_바로_돌아오고_나중에_끝난다() throws InterruptedException {
        JobExecution started = launcher.startAsync(CollectMode.WEEKLY, null);

        JobExecution finished = jobRepository.getJobExecution(started.getId());
        for (int i = 0; i < 100 && finished.isRunning(); i++) {
            Thread.sleep(100);
            finished = jobRepository.getJobExecution(started.getId());
        }
        assertThat(finished.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // baseDate가 없으면 서울 기준 오늘로 채워서 기록한다
        assertThat(finished.getJobParameters().getString(CollectJobParameters.BASE_DATE)).isNotNull();
    }

    private JobExecution run(CollectMode mode) {
        try {
            return jobOperator.start(collectTradeJob, CollectJobParameters.of(mode, BASE_DATE, RUN_ID.incrementAndGet()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 다른 프로세스에서 startedAgo 전에 시작해 아직 STARTED인 실행 (강제 종료로 남은 기록도 같은 모습이다) */
    private void fakeRunning(CollectMode mode, Duration startedAgo) {
        JobParameters parameters = CollectJobParameters.of(mode, BASE_DATE, RUN_ID.incrementAndGet());
        JobInstance instance = jobRepository.createJobInstance(RunningCollectJobs.JOB_NAME, parameters);
        JobExecution execution = jobRepository.createJobExecution(instance, parameters, new ExecutionContext());
        execution.setStatus(BatchStatus.STARTED);
        execution.setStartTime(LocalDateTime.now().minus(startedAgo));
        jobRepository.update(execution);
    }

    private Map<String, Object> summary(JobExecution execution) {
        return summary(execution, CollectBatchConfig.COLLECT_STEP);
    }

    private Map<String, Object> summary(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().equals(stepName))
                .findFirst().orElseThrow()
                .getExecutionContext().toMap();
    }

    private static String stepExitCode(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().equals(stepName))
                .findFirst().orElseThrow()
                .getExitStatus().getExitCode();
    }

    private long count(String where) {
        return jdbc.queryForObject("SELECT count(*) FROM collect_history WHERE " + where, Long.class);
    }
}
