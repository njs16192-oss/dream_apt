package com.dreamapt.collect.batch;

import java.time.Clock;
import java.time.OffsetDateTime;

import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

import com.dreamapt.collect.ApiRequestThrottle;
import com.dreamapt.collect.CollectResult;
import com.dreamapt.collect.TradeApiException;
import com.dreamapt.collect.TradeCollectService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 구×월 하나를 수집하고 결과를 collect_history에 남긴다. 일시 오류는 백오프 재시도한다 (ADR 006 3-②).
 * 배치와 관리 API 단건 수집이 함께 쓴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollectTargetRunner {

    private final TradeCollectService collectService;
    private final CollectHistoryRepository historyRepository;
    private final ApiRequestThrottle throttle;
    private final RetryTemplate collectRetryTemplate;
    private final Clock clock;

    /**
     * 성공하면 결과를, 실패하면 실패를 기록한 뒤 마지막 예외를 그대로 던진다.
     *
     * @param jobExecutionId 배치 실행 id. 관리 API 단건 수집이면 null
     */
    public CollectResult collect(CollectTarget target, Long jobExecutionId) {
        long callsBefore = throttle.requestCount();
        try {
            CollectResult result = collectWithRetry(target);
            historyRepository.recordSuccess(target, result, callsSince(callsBefore), jobExecutionId, now());
            return result;
        } catch (RuntimeException e) {
            historyRepository.recordFailure(target, failureReason(e), callsSince(callsBefore), jobExecutionId, now());
            throw e;
        }
    }

    private CollectResult collectWithRetry(CollectTarget target) {
        try {
            return collectRetryTemplate.execute(() -> collectService.collect(target.sggCd(), target.dealYm()));
        } catch (RetryException e) {
            // 재시도 대상이 아니거나 재시도를 다 써서 실패. 마지막 예외를 그대로 올린다
            if (e.getLastException() instanceof RuntimeException last) {
                throw last;
            }
            throw new IllegalStateException("수집 실패: " + target, e.getLastException());
        }
    }

    private int callsSince(long before) {
        return Math.toIntExact(throttle.requestCount() - before);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String failureReason(RuntimeException e) {
        String kind = e instanceof TradeApiException apiError ? apiError.kind().name() : e.getClass().getSimpleName();
        return kind + ": " + e.getMessage();
    }

    /** 일시 오류(TRANSIENT)만 재시도한다 */
    static boolean isRetryable(Throwable e) {
        return e instanceof TradeApiException apiError && apiError.kind() == TradeApiException.Kind.TRANSIENT;
    }
}
