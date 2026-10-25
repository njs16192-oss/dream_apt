package com.dreamapt.complex;

import static com.dreamapt.common.ApiDescriptions.BULK;
import static com.dreamapt.common.ApiDescriptions.BULK_EXCLUDED;
import static com.dreamapt.common.ApiDescriptions.MANWON;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 단지의 면적 그룹 하나. 요약값은 일괄 거래를 제외한다 (ADR 007 ②). */
@Schema(description = "단지의 면적 그룹 요약. 요약값은 " + BULK_EXCLUDED)
public record AreaGroupSummary(
        @Schema(description = "면적 그룹: 전용면적(㎡)을 정수로 반올림한 값", example = "85") int areaGroup,
        @Schema(description = "이 그룹에 묶인 전용면적 원값(㎡), 오름차순. 일괄 거래의 면적도 포함한다",
                example = "[84.95, 84.98, 84.99]")
        List<BigDecimal> exclusiveAreas,
        @Schema(description = "유효 거래 수. " + BULK_EXCLUDED, example = "289") int tradeCount,
        @Schema(description = "일괄 거래 수 (tradeCount에 들어 있지 않음). " + BULK, example = "0") int bulkCount,
        @Schema(description = "마지막 계약일. " + BULK_EXCLUDED + " 일반 거래가 없으면 null", nullable = true)
        LocalDate lastDealDate,
        @Schema(description = "마지막 계약일의 거래 금액. " + MANWON + " " + BULK_EXCLUDED
                + " 같은 날 여러 건이면 가장 늦게 수집된 거래. 일반 거래가 없으면 null", example = "245000", nullable = true)
        Integer lastDealAmount) {
}
