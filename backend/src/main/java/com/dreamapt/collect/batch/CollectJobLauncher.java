package com.dreamapt.collect.batch;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * collectTradeJob 실행 (ADR 006 3-⑤). 명령줄(batch 프로필)은 끝날 때까지 기다리고, 관리 API는 비동기로 시작한다.
 * 비동기용 JobOperator는 빈으로 등록하지 않는다. 등록하면 Boot가 만드는 기본(동기) JobOperator와 겹친다.
 */
@Slf4j
@Component
public class CollectJobLauncher {

    private final JobOperator jobOperator;
    private final TaskExecutorJobOperator asyncJobOperator;
    private final Job collectTradeJob;
    private final Clock clock;
    private final AtomicLong lastRunId = new AtomicLong();

    public CollectJobLauncher(JobOperator jobOperator, JobRepository jobRepository, Job collectTradeJob, Clock clock)
            throws Exception {
        this.jobOperator = jobOperator;
        this.collectTradeJob = collectTradeJob;
        this.clock = clock;

        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("collect-job-");
        // 모드가 3개라 동시에 돌 수 있는 실행도 최대 3개다 (같은 모드는 리스너가 막는다)
        executor.setConcurrencyLimit(CollectMode.values().length);
        // JobOperator는 JobRegistry가 필수다. start(Job, ...)는 registry를 쓰지 않지만, 이 Job만 담은 것을 넣어 둔다
        MapJobRegistry jobRegistry = new MapJobRegistry();
        jobRegistry.register(collectTradeJob);
        this.asyncJobOperator = new TaskExecutorJobOperator();
        this.asyncJobOperator.setJobRepository(jobRepository);
        this.asyncJobOperator.setJobRegistry(jobRegistry);
        this.asyncJobOperator.setTaskExecutor(executor);
        this.asyncJobOperator.afterPropertiesSet();
    }

    /** 끝날 때까지 기다린다 (명령줄 실행) */
    public JobExecution run(CollectMode mode, LocalDate baseDate) {
        return start(jobOperator, mode, baseDate);
    }

    /** 시작만 하고 바로 돌아온다 (관리 API). 결과는 실행 id로 조회한다 */
    public JobExecution startAsync(CollectMode mode, LocalDate baseDate) {
        return start(asyncJobOperator, mode, baseDate);
    }

    private JobExecution start(JobOperator operator, CollectMode mode, LocalDate baseDate) {
        JobParameters parameters = CollectJobParameters.of(mode,
                baseDate != null ? baseDate : LocalDate.now(clock), nextRunId());
        try {
            JobExecution execution = operator.start(collectTradeJob, parameters);
            log.info("collectTradeJob 실행 jobExecutionId={} status={} parameters={}", execution.getId(),
                    execution.getStatus(), parameters);
            return execution;
        } catch (Exception e) {
            throw new IllegalStateException("collectTradeJob 시작 실패: " + parameters, e);
        }
    }

    /** 실행마다 새 JobInstance가 되도록 시작 시각(ms)을 쓴다. 같은 ms에 두 번 시작해도 겹치지 않게 1씩 올린다 */
    private long nextRunId() {
        return lastRunId.updateAndGet(last -> Math.max(last + 1, clock.millis()));
    }
}
