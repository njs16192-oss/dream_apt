package com.dreamapt.complex;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 단지의 면적 그룹 하나. 요약값은 일괄 거래를 제외한다 (ADR 007 ②).
 *
 * @param areaGroup      전용면적 정수 반올림(㎡)
 * @param exclusiveAreas 이 그룹에 묶인 전용면적 원값(㎡), 오름차순. 일괄 거래의 면적도 포함한다
 * @param tradeCount     유효 거래 수, 일괄 거래 제외
 * @param bulkCount      일괄 거래 수
 * @param lastDealDate   마지막 계약일, 일괄 거래 제외. 일반 거래가 없으면 null
 * @param lastDealAmount 마지막 계약일의 거래 금액(만원), 일괄 거래 제외. 같은 날 여러 건이면 가장 늦게 수집된 거래
 */
public record AreaGroupSummary(
        int areaGroup,
        List<BigDecimal> exclusiveAreas,
        int tradeCount,
        int bulkCount,
        LocalDate lastDealDate,
        Integer lastDealAmount) {
}
