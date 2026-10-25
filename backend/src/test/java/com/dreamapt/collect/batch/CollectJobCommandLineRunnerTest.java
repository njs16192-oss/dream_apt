package com.dreamapt.collect.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.MetaDataInstanceFactory;
import org.springframework.boot.DefaultApplicationArguments;

class CollectJobCommandLineRunnerTest {

    private final CollectJobLauncher launcher = mock(CollectJobLauncher.class);
    private final CollectJobCommandLineRunner runner = new CollectJobCommandLineRunner(launcher);

    @Test
    void 인자로_모드와_기준일을_받아_실행하고_결과를_종료_코드로_돌려준다() {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setExitStatus(CollectExitStatus.completedWithFailures("1개 구×월 실패"));
        given(launcher.run(CollectMode.WEEKLY, LocalDate.of(2026, 10, 25))).willReturn(execution);

        runner.run(new DefaultApplicationArguments("--mode=weekly", "--baseDate=2026-10-25"));

        assertThat(runner.getExitCode()).isEqualTo(CollectJobExitCodes.PARTIALLY_FAILED);
    }

    @Test
    void 기준일이_없으면_launcher가_오늘로_채운다() {
        JobExecution execution = MetaDataInstanceFactory.createJobExecution();
        execution.setStatus(BatchStatus.COMPLETED);
        execution.setExitStatus(ExitStatus.COMPLETED);
        given(launcher.run(CollectMode.DAILY, null)).willReturn(execution);

        runner.run(new DefaultApplicationArguments("--mode=daily"));

        verify(launcher).run(CollectMode.DAILY, null);
        assertThat(runner.getExitCode()).isEqualTo(CollectJobExitCodes.SUCCESS);
    }

    @Test
    void 모드가_없거나_틀리면_실행하지_않고_기동을_실패시킨다() {
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments("--mode=monthly")))
                .isInstanceOf(IllegalArgumentException.class);

        verify(launcher, never()).run(any(), any());
    }
}
