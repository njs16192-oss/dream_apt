package com.dreamapt.collect.batch;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import com.dreamapt.geocode.ComplexGeocodeService;
import com.dreamapt.geocode.GeocodeResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 수집 뒤 좌표 없는 신규 단지를 지오코딩한다 (ADR 010). 실패한 단지는 다시 시도하지 않는다 (retryFailed=false).
 * <ul>
 * <li>limit이 0이거나 API 키가 없으면 건너뛴다</li>
 * <li>지오코딩이 실패해도(키 거부 등) 예외를 잡고 COMPLETED로 끝낸다. 수집 결과와 종료 코드는 수집 step이 정한다
 * ({@link CollectStepExitStatusListener})</li>
 * </ul>
 * 결과 요약은 StepExecution의 ExecutionContext(키 {@code summary.*})와 로그에 남긴다.
 */
@Slf4j
@RequiredArgsConstructor
public class GeocodeComplexesTasklet implements Tasklet {

    private final ComplexGeocodeService geocodeService;
    private final int limit;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext summary = chunkContext.getStepContext().getStepExecution().getExecutionContext();
        if (limit <= 0) {
            log.info("지오코딩 건너뜀: geocode-limit=0");
            summary.putString("summary.skipped", "disabled");
            return RepeatStatus.FINISHED;
        }
        if (!geocodeService.ready()) {
            log.info("지오코딩 건너뜀: 도로명주소 API 키 없음");
            summary.putString("summary.skipped", "no-keys");
            return RepeatStatus.FINISHED;
        }
        try {
            GeocodeResult result = geocodeService.geocode(limit, false);
            summary.putInt("summary.attempted", result.attempted());
            summary.putInt("summary.succeeded", result.succeeded());
            summary.putInt("summary.failed", result.attempted() - result.succeeded() - result.transientError());
            summary.putInt("summary.transientError", result.transientError());
            summary.put("summary.stopped", result.stopped());
            summary.putLong("summary.remaining", result.remaining());
            contribution.incrementWriteCount(result.succeeded());
        } catch (RuntimeException e) {
            log.error("지오코딩 실패, 수집 결과에는 영향 없음: {}", e.getMessage(), e);
            summary.putString("summary.error", String.valueOf(e.getMessage()));
        }
        return RepeatStatus.FINISHED;
    }
}
