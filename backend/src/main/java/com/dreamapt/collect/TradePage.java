package com.dreamapt.collect;

import java.util.List;

/**
 * API 응답 한 페이지. totalCount는 전체 페이지를 합친 건수.
 */
public record TradePage(List<TradeRecord> items, int totalCount) {
}
