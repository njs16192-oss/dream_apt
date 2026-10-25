package com.dreamapt.complex;

import java.util.List;

/**
 * 단지 상세.
 *
 * @param tradeCount   유효 거래 수, 일괄 거래 제외
 * @param bulkCount    일괄 거래 수
 * @param bulkRatio    유효 거래 중 일괄 거래 비율 (0–1). 유효 거래가 없으면 0
 * @param bulkDominant 일괄 거래 비율이 {@link ComplexQueryService#BULK_DOMINANT_RATIO} 이상. 화면은 추이 대신 안내를 보여준다
 * @param areaGroups   면적 그룹 오름차순
 */
public record ComplexDetail(
        String aptSeq,
        String name,
        String sggCd,
        String sggName,
        String umdName,
        Integer buildYear,
        String roadAddress,
        int tradeCount,
        int bulkCount,
        double bulkRatio,
        boolean bulkDominant,
        List<AreaGroupSummary> areaGroups) {
}
