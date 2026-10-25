package com.dreamapt.collect.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 같은 모드로 실행 중인 collectTradeJob 조회 (ADR 006 3-⑦). 관리 API(409)와 시작 리스너가 함께 쓴다.
 * 시작 후 staleRunningAfter가 지난 STARTING/STARTED 기록은 강제 종료로 남은 것으로 보고 실행 중에서 뺀다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunningCollectJobs {

    public static final String JOB_NAME = "collectTradeJob";

    private final JobRepository jobRepository;
    private final CollectBatchProperties properties;
    private final Clock clock;

    /**
     * @param exceptId 이 실행 자신은 빼고 찾는다 (시작 리스너에서). 없으면 null
     */
    public List<JobExecution> findRunning(CollectMode mode, Long exceptId) {
        LocalDateTime staleBefore = now().minus(properties.staleRunningAfter());
        return jobRepository.findRunningJobExecutions(JOB_NAME).stream()
                .filter(e -> exceptId == null || e.getId() != exceptId)
                .filter(e -> mode.parameterValue().equals(e.getJobParameters().getString(CollectJobParameters.MODE)))
                .filter(e -> {
                    if (startedAt(e).isBefore(staleBefore)) {
                        log.warn("오래된 실행 기록은 실행 중으로 보지 않음 jobExecutionId={} mode={} startedAt={} 기준={}",
                                e.getId(), mode.parameterValue(), startedAt(e), properties.staleRunningAfter());
                        return false;
                    }
                    return true;
                })
                .sorted(Comparator.comparing(JobExecution::getId))
                .toList();
    }

    /** 실행 기록이 오래된 것인가 (recover 허용 조건) */
    public boolean isStale(JobExecution execution) {
        return execution.isRunning()
                && Duration.between(startedAt(execution), now()).compareTo(properties.staleRunningAfter()) > 0;
    }

    // STARTING은 아직 시작 시각이 없어서 생성 시각으로 대신한다
    private static LocalDateTime startedAt(JobExecution execution) {
        return execution.getStartTime() != null ? execution.getStartTime() : execution.getCreateTime();
    }

    // Spring Batch는 메타데이터 시각을 JVM 기본 시간대의 LocalDateTime으로 기록하므로 같은 기준으로 비교한다
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }
}
