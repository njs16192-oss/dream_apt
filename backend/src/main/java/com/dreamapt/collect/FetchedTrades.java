package com.dreamapt.collect;

import java.util.List;

/**
 * 한 시군구·한 달 치 API 응답 전체. totalCount는 마지막으로 받은 페이지의 값이다.
 */
public record FetchedTrades(List<TradeRecord> records, int totalCount) {

    /**
     * 받은 건수가 응답의 totalCount와 같은가. 다르면 대조의 전제(응답 = 구×월 전체)가 깨진다.
     */
    public boolean complete() {
        return records.size() == totalCount;
    }
}
