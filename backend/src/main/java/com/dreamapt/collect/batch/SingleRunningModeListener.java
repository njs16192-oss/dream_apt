package com.dreamapt.collect.batch;

import java.util.List;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 같은 모드의 다른 실행이 진행 중이면 이 실행을 시작하지 않는다 (ADR 006 3-⑦).
 * beforeJob에서 STOPPING으로 바꾸면 첫 step 전에 중단돼 STOPPED로 끝나고, afterJob에서 ExitStatus를 ALREADY_RUNNING으로 남긴다.
 */
@Slf4j
@RequiredArgsConstructor
public class SingleRunningModeListener implements JobExecutionListener {

    private static final String REJECTED_BY = "collect.alreadyRunning";

    private final RunningCollectJobs runningJobs;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        CollectMode mode = CollectJobParameters.mode(jobExecution.getJobParameters());
        List<JobExecution> running = runningJobs.findRunning(mode, jobExecution.getId());
        if (!running.isEmpty()) {
            long otherId = running.get(0).getId();
            log.warn("같은 모드의 실행이 진행 중이라 시작하지 않음 mode={} 진행 중={}", mode.parameterValue(), otherId);
            jobExecution.getExecutionContext().putLong(REJECTED_BY, otherId);
            jobExecution.setStatus(BatchStatus.STOPPING);
        }
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        if (jobExecution.getExecutionContext().containsKey(REJECTED_BY)) {
            jobExecution.setExitStatus(CollectExitStatus.alreadyRunning(
                    "진행 중인 실행: " + jobExecution.getExecutionContext().getLong(REJECTED_BY)));
        }
    }
}
