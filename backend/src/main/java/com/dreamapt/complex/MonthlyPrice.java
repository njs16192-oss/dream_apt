package com.dreamapt.complex;

import java.time.YearMonth;

/**
 * 단지·면적 그룹의 월별 가격. 일괄 거래 제외 (ADR 007 ②④).
 *
 * @param medianAmount 계약 금액 중앙값(만원). 짝수 건이면 가운데 두 값의 평균을 HALF_UP 반올림
 * @param count        유효 거래 수, 일괄 거래 제외
 * @param provisional  집계 중인 달. 신고·해제가 더 반영되어 값이 바뀔 수 있다
 */
public record MonthlyPrice(YearMonth month, int medianAmount, int count, boolean provisional) {
}
