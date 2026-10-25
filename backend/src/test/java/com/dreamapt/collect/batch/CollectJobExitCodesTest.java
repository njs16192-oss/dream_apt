package com.dreamapt.collect.batch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;

/**
 * Job 결과 → 프로세스 종료 코드 (ADR 006 3-⑥)
 */
class CollectJobExitCodesTest {

    @ParameterizedTest(name = "{0} / {1} → {2}")
    @CsvSource({
            "COMPLETED, COMPLETED, 0",
            "FAILED, FAILED, 2",
            "COMPLETED, COMPLETED_WITH_FAILURES, 3",
            "STOPPED, ALREADY_RUNNING, 4",
            // 그 밖의 비정상 종료도 실패로 본다
            "STOPPED, STOPPED, 2",
            "UNKNOWN, UNKNOWN, 2",
            "COMPLETED, NOOP, 2",
    })
    void 종료_코드(BatchStatus status, String exitCode, int expected) {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        execution.setStatus(status);
        execution.setExitStatus(new ExitStatus(exitCode));

        assertThat(CollectJobExitCodes.of(execution)).isEqualTo(expected);
    }
}
