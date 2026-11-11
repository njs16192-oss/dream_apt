package com.dreamapt.collect.batch;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import com.dreamapt.newhigh.NewHighDetectResult;
import com.dreamapt.newhigh.NewHighDetectService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 수집·지오코딩 뒤 최근 3개월 계약 거래의 신고가를 판단해 기록한다 (ADR 011 ⑤).
 * <ul>
 * <li>기준일은 Job 파라미터 baseDate. 없으면 Clock의 오늘 ({@link CollectTargetsTasklet}과 같다)</li>
 * <li>판단이 실패해도 예외를 잡고 COMPLETED로 끝낸다. 종료 코드는 수집 step이 정한다
 * ({@link CollectStepExitStatusListener}). 다음 실행이 같은 3개월을 다시 판단하므로 빠지는 거래는 없다</li>
 * </ul>
 * 결과 요약은 StepExecution의 ExecutionContext(키 {@code summary.*})와 로그에 남긴다.
 */
@Slf4j
@RequiredArgsConstructor
public class DetectNewHighsTasklet implements Tasklet {

    private final NewHighDetectService detectService;
    private final LocalDate baseDateParameter;
    private final Clock clock;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        ExecutionContext summary = chunkContext.getStepContext().getStepExecution().getExecutionContext();
        LocalDate baseDate = baseDateParameter != null ? baseDateParameter : LocalDate.now(clock);
        try {
            NewHighDetectResult result = detectService.detect(baseDate);
            summary.putString("summary.from", result.from().toString());
            summary.putInt("summary.recorded", result.recorded());
            summary.putInt("summary.notified", result.notified());
            contribution.incrementWriteCount(result.recorded());
        } catch (RuntimeException e) {
            log.error("신고가 판단 실패, 수집 결과에는 영향 없음: {}", e.getMessage(), e);
            summary.putString("summary.error", String.valueOf(e.getMessage()));
        }
        return RepeatStatus.FINISHED;
    }
}
