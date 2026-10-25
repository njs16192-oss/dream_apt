package com.dreamapt.collect.batch;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import com.dreamapt.collect.ApiRequestThrottle;
import com.dreamapt.collect.TradeApiException;
import com.dreamapt.region.Region;
import com.dreamapt.region.RegionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대상 구×월을 execute 한 번에 하나씩 처리한다 (ADR 006 3-①). 실행마다 새로 만들어지는 step 범위 빈이다.
 * <ul>
 * <li>구×월 하나의 실패는 기록하고 다음으로 넘어간다. 일부 실패면 ExitStatus COMPLETED_WITH_FAILURES</li>
 * <li>한도 초과·인증 실패는 남은 대상도 모두 실패하므로 예외를 던져 step을 FAILED로 끝낸다</li>
 * <li>backfill은 이미 성공한 구×월을 건너뛰고, 호출 상한에 닿으면 멈춘다. 남은 대상은 다음 실행이 이어서 한다</li>
 * </ul>
 * 처리 결과 요약은 StepExecution의 ExecutionContext(키 {@code summary.*})와 로그에 남긴다.
 */
@Slf4j
@RequiredArgsConstructor
public class CollectTargetsTasklet implements Tasklet, StepExecutionListener {

    private final CollectMode mode;
    private final LocalDate baseDateParameter;
    private final RegionRepository regionRepository;
    private final CollectHistoryRepository historyRepository;
    private final CollectTargetRunner runner;
    private final ApiRequestThrottle throttle;
    private final CollectBatchProperties properties;
    private final Clock clock;

    private List<CollectTarget> targets = List.of();
    private int next;
    private int skipped;
    private int succeeded;
    private int failed;
    private long callsBefore;
    private boolean callLimitReached;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        LocalDate baseDate = baseDateParameter != null ? baseDateParameter : LocalDate.now(clock);
        List<String> sggCds = regionRepository.findByCollectEnabledTrueOrderBySggCd().stream()
                .map(Region::getSggCd)
                .toList();
        List<CollectTarget> planned = CollectTargetPlanner.plan(mode, baseDate, sggCds, properties.backfillStart());

        if (mode == CollectMode.BACKFILL) {
            Set<CollectTarget> done = historyRepository.findSucceeded(sggCds);
            targets = planned.stream().filter(t -> !done.contains(t)).toList();
            skipped = planned.size() - targets.size();
        } else {
            targets = planned;
        }
        callsBefore = throttle.requestCount();
        log.info("수집 시작 mode={} baseDate={} 구={} 대상={} 건너뜀={}",
                mode.parameterValue(), baseDate, sggCds.size(), targets.size(), skipped);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        if (next >= targets.size()) {
            return RepeatStatus.FINISHED;
        }
        if (mode == CollectMode.BACKFILL && callsUsed() >= properties.backfillMaxCalls()) {
            callLimitReached = true;
            log.info("호출 상한에 닿아 멈춤 calls={} limit={} 남은 대상={}",
                    callsUsed(), properties.backfillMaxCalls(), targets.size() - next);
            return RepeatStatus.FINISHED;
        }

        CollectTarget target = targets.get(next++);
        Long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();
        contribution.incrementReadCount();
        try {
            runner.collect(target, jobExecutionId);
            succeeded++;
            contribution.incrementWriteCount(1);
        } catch (TradeApiException e) {
            failed++;
            if (e.kind().abortsRun()) {
                log.error("실행 중단 target={} kind={}: {}", target, e.kind(), e.getMessage());
                throw e;
            }
            log.warn("구×월 수집 실패, 다음으로 진행 target={} kind={}: {}", target, e.kind(), e.getMessage(), e);
        } catch (RuntimeException e) {
            failed++;
            log.warn("구×월 수집 실패, 다음으로 진행 target={}: {}", target, e.getMessage(), e);
        }
        return next < targets.size() ? RepeatStatus.CONTINUABLE : RepeatStatus.FINISHED;
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        int remaining = targets.size() - next;
        ExecutionContext summary = stepExecution.getExecutionContext();
        summary.putInt("summary.targets", targets.size());
        summary.putInt("summary.skipped", skipped);
        summary.putInt("summary.succeeded", succeeded);
        summary.putInt("summary.failed", failed);
        summary.putInt("summary.remaining", remaining);
        summary.putLong("summary.apiCalls", callsUsed());
        summary.put("summary.callLimitReached", callLimitReached);
        log.info("수집 종료 mode={} status={} 대상={} 성공={} 실패={} 건너뜀={} 남음={} 호출={}",
                mode.parameterValue(), stepExecution.getStatus(), targets.size(), succeeded, failed, skipped,
                remaining, callsUsed());

        boolean completed = ExitStatus.COMPLETED.getExitCode().equals(stepExecution.getExitStatus().getExitCode());
        if (completed && failed > 0) {
            return CollectExitStatus.completedWithFailures(failed + "개 구×월 실패");
        }
        return null;
    }

    private long callsUsed() {
        return throttle.requestCount() - callsBefore;
    }
}
