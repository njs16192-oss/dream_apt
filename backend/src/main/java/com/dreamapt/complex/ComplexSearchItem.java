package com.dreamapt.complex;

/**
 * 단지 검색 결과 한 건. 같은 이름 단지가 많아(19.4%) 구·법정동·준공연도를 함께 준다 (ADR 007 ⑥).
 *
 * @param recentTradeCount 기준일 전 12개월 유효 거래 수, 일괄 거래 제외. 정렬 기준이다
 */
public record ComplexSearchItem(
        String aptSeq,
        String name,
        String sggCd,
        String sggName,
        String umdName,
        Integer buildYear,
        int recentTradeCount) {
}
