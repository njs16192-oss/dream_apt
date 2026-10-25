package com.dreamapt.region;

import java.time.YearMonth;

/**
 * 구의 월별 거래량 (ADR 007 ②).
 *
 * @param count       유효 거래 수, 일괄 거래 제외
 * @param bulkCount   일괄 거래 수 (count에 들어 있지 않음)
 * @param provisional 집계 중인 달. 신고·해제가 더 반영되어 값이 바뀔 수 있다
 */
public record MonthlyVolume(YearMonth month, int count, int bulkCount, boolean provisional) {
}
