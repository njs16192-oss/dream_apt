package com.dreamapt.collect.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

import com.dreamapt.collect.ApiRequestThrottle;
import com.dreamapt.collect.CollectResult;
import com.dreamapt.collect.DataGoKrProperties;
import com.dreamapt.collect.TradeApiException;
import com.dreamapt.collect.TradeApiException.Kind;
import com.dreamapt.collect.TradeCollectService;

/**
 * 일시 오류만 재시도하고, 성공·실패를 collect_history에 남긴다 (ADR 006 3-②, 3-③)
 */
class CollectTargetRunnerTest {

    private static final CollectTarget TARGET = new CollectTarget("11500", YearMonth.of(2026, 9));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);
    private static final CollectResult RESULT = new CollectResult("11500", "202609", 252, 108, 3, 1, 248, 0, List.of());

    private final TradeCollectService collectService = mock(TradeCollectService.class);
    private final CollectHistoryRepository historyRepository = mock(CollectHistoryRepository.class);
    private final ApiRequestThrottle throttle = new ApiRequestThrottle(new DataGoKrProperties(null, null, Duration.ZERO));
    private final RetryTemplate retryTemplate = new RetryTemplate(RetryPolicy.builder()
            .maxRetries(2)
            .delay(Duration.ofMillis(1))
            .predicate(CollectTargetRunner::isRetryable)
            .build());
    private final CollectTargetRunner runner = new CollectTargetRunner(collectService, historyRepository, throttle,
            retryTemplate, CLOCK);

    @Test
    void 성공하면_결과를_기록한다() {
        given(collectService.collect("11500", "202609")).willAnswer(inv -> {
            throttle.acquire();
            return RESULT;
        });

        assertThat(runner.collect(TARGET, 7L)).isEqualTo(RESULT);

        verify(historyRepository).recordSuccess(TARGET, RESULT, 1, 7L, NOW);
    }

    @Test
    void 일시_오류는_재시도하고_호출_수에_재시도를_포함한다() {
        given(collectService.collect("11500", "202609"))
                .willAnswer(inv -> {
                    throttle.acquire();
                    throw new TradeApiException(Kind.TRANSIENT, "timeout");
                })
                .willAnswer(inv -> {
                    throttle.acquire();
                    return RESULT;
                });

        runner.collect(TARGET, null);

        verify(collectService, times(2)).collect("11500", "202609");
        verify(historyRepository).recordSuccess(TARGET, RESULT, 2, null, NOW);
    }

    @Test
    void 재시도를_다_쓰면_실패를_기록하고_마지막_예외를_던진다() {
        given(collectService.collect("11500", "202609")).willThrow(new TradeApiException(Kind.TRANSIENT, "503"));

        assertThatThrownBy(() -> runner.collect(TARGET, 7L))
                .isInstanceOfSatisfying(TradeApiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));

        // 첫 시도 + 재시도 2회
        verify(collectService, times(3)).collect("11500", "202609");
        verify(historyRepository).recordFailure(eq(TARGET), startsWith("TRANSIENT: 503"), anyInt(), eq(7L), eq(NOW));
    }

    @Test
    void 일시_오류가_아니면_재시도하지_않는다() {
        given(collectService.collect("11500", "202609")).willThrow(new TradeApiException(Kind.QUOTA_EXCEEDED, "한도"));

        assertThatThrownBy(() -> runner.collect(TARGET, 7L)).isInstanceOf(TradeApiException.class);

        verify(collectService, times(1)).collect(anyString(), anyString());
        verify(historyRepository).recordFailure(eq(TARGET), startsWith("QUOTA_EXCEEDED"), anyInt(), anyLong(), any());
        verify(historyRepository, never()).recordSuccess(any(), any(), anyInt(), any(), any());
    }

    @Test
    void API_오류가_아닌_예외도_실패로_기록한다() {
        given(collectService.collect("11500", "202609")).willThrow(new IllegalStateException("DB 오류"));

        assertThatThrownBy(() -> runner.collect(TARGET, 7L)).isInstanceOf(IllegalStateException.class);

        verify(collectService, times(1)).collect(anyString(), anyString());
        verify(historyRepository).recordFailure(eq(TARGET), eq("IllegalStateException: DB 오류"), anyInt(), anyLong(),
                any());
    }
}
