package com.dreamapt.collect.batch;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.step.StepExecution;

import lombok.extern.slf4j.Slf4j;

/**
 * Job의 ExitStatus를 수집 step 기준으로 맞춘다 (ADR 006 3-⑥).
 * Spring Batch는 마지막 step의 ExitStatus를 Job ExitStatus로 쓴다. 수집 뒤에 지오코딩 step이 붙어서,
 * 수집이 일부 실패(COMPLETED_WITH_FAILURES)해도 지오코딩이 COMPLETED면 종료 코드가 0이 된다.
 * Job이 COMPLETED이고 수집 step의 ExitStatus가 COMPLETED가 아니면 그 값을 Job에 다시 싣는다.
 * <p>
 * 같은 모드가 실행 중(ALREADY_RUNNING)이면 첫 step 전에 멈춰 수집 step 실행이 없으므로 이 listener는 아무것도 하지 않는다.
 */
@Slf4j
public class CollectStepExitStatusListener implements JobExecutionListener {

    private final String collectStepName;

    public CollectStepExitStatusListener(String collectStepName) {
        this.collectStepName = collectStepName;
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
            return;
        }
        for (StepExecution step : jobExecution.getStepExecutions()) {
            if (collectStepName.equals(step.getStepName())
                    && !ExitStatus.COMPLETED.getExitCode().equals(step.getExitStatus().getExitCode())) {
                log.info("Job ExitStatus를 수집 step 기준으로 맞춤: {} → {}",
                        jobExecution.getExitStatus().getExitCode(), step.getExitStatus().getExitCode());
                jobExecution.setExitStatus(step.getExitStatus());
                return;
            }
        }
    }
}
