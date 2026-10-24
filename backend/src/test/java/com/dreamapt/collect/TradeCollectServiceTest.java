package com.dreamapt.collect;

import static com.dreamapt.collect.TradeFixtures.MYEONGDEOK;
import static com.dreamapt.collect.TradeFixtures.load;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 원본 완전성 가드: 받은 건수가 totalCount와 다르면 1회 재시도 후 실패 (ADR 004 3-⑤)
 */
class TradeCollectServiceTest {

    private final TradeApiClient apiClient = mock(TradeApiClient.class);
    private final TradeReconcileService reconcileService = mock(TradeReconcileService.class);
    private final TradeCollectService collectService = new TradeCollectService(apiClient, reconcileService);

    private final List<TradeRecord> records = load(MYEONGDEOK);

    @Test
    void 건수가_맞으면_한_번만_받아서_저장한다() {
        given(apiClient.fetch("11500", "202609")).willReturn(new FetchedTrades(records, 2));
        given(reconcileService.save(anyString(), anyString(), anyList())).willReturn(ReconcileResult.EMPTY);

        collectService.collect("11500", "202609");

        verify(apiClient, times(1)).fetch("11500", "202609");
        verify(reconcileService).save("11500", "202609", records);
    }

    @Test
    void 처음에_건수가_다르면_다시_받고_맞으면_다시_받은_레코드로_저장한다() {
        List<TradeRecord> partial = records.subList(0, 1);
        given(apiClient.fetch("11500", "202609"))
                .willReturn(new FetchedTrades(partial, 2))
                .willReturn(new FetchedTrades(records, 2));
        given(reconcileService.save(anyString(), anyString(), anyList()))
                .willReturn(new ReconcileResult(1, 2, 0, 0, 0, List.of()));

        CollectResult result = collectService.collect("11500", "202609");

        verify(apiClient, times(2)).fetch("11500", "202609");
        verify(reconcileService).save("11500", "202609", records);
        assertThat(result.fetched()).isEqualTo(2);
    }

    @Test
    void 다시_받아도_건수가_다르면_저장하지_않고_실패한다() {
        given(apiClient.fetch("11500", "202609"))
                .willReturn(new FetchedTrades(records.subList(0, 1), 2))
                .willReturn(new FetchedTrades(records, 3));

        assertThatThrownBy(() -> collectService.collect("11500", "202609"))
                .isInstanceOf(TradeApiException.class)
                .hasMessageContaining("totalCount=3");

        verify(apiClient, times(2)).fetch("11500", "202609");
        verify(reconcileService, never()).save(anyString(), anyString(), any());
    }
}
