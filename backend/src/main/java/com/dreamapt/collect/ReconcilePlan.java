package com.dreamapt.collect;

import java.util.List;

/**
 * 구×월 대조 결과. unchanged + updates + inserts = 받은 레코드 수.
 *
 * @param unchanged 값이 같아 그대로 두는 행 id
 * @param updates   응답 값으로 갱신할 행 (missing 표시 해제 포함)
 * @param inserts   새로 추가할 레코드
 * @param missing   짝이 없는 DB 행. 이미 missing으로 표시된 행도 포함한다
 */
public record ReconcilePlan(
        List<Long> unchanged,
        List<Update> updates,
        List<TradeRecord> inserts,
        List<ExistingTrade> missing) {

    public record Update(long id, TradeRecord record) {
    }

    /**
     * 이번 수집에서 새로 사라진 행 (missing 표시 대상)
     */
    public List<ExistingTrade> newlyMissing() {
        return missing.stream().filter(t -> !t.missing()).toList();
    }
}
