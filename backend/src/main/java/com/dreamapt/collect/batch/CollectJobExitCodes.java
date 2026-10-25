package com.dreamapt.collect.batch;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;

/**
 * Job 결과 → 프로세스 종료 코드 (ADR 006 3-⑥). CronJob은 0이 아니면 실패로 본다.
 * 1(기동 실패·잘못된 파라미터)은 Spring Boot가 기동 실패 때 쓰는 값이라 여기서 만들지 않는다.
 */
public final class CollectJobExitCodes {

    public static final int SUCCESS = 0;
    public static final int JOB_FAILED = 2;
    public static final int PARTIALLY_FAILED = 3;
    public static final int ALREADY_RUNNING = 4;

    private CollectJobExitCodes() {
    }

    public static int of(JobExecution execution) {
        String exitCode = execution.getExitStatus().getExitCode();
        if (CollectExitStatus.ALREADY_RUNNING.equals(exitCode)) {
            return ALREADY_RUNNING;
        }
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            return JOB_FAILED;
        }
        if (CollectExitStatus.COMPLETED_WITH_FAILURES.equals(exitCode)) {
            return PARTIALLY_FAILED;
        }
        return ExitStatus.COMPLETED.getExitCode().equals(exitCode) ? SUCCESS : JOB_FAILED;
    }
}
