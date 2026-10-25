package com.dreamapt.complex;

import static com.dreamapt.common.ApiDescriptions.BULK_EXCLUDED;
import static com.dreamapt.common.ApiDescriptions.MANWON;
import static com.dreamapt.common.ApiDescriptions.PROVISIONAL;
import static com.dreamapt.common.ApiDescriptions.YEAR_MONTH;

import java.time.YearMonth;

import io.swagger.v3.oas.annotations.media.Schema;

/** 단지·면적 그룹의 월별 가격. 일괄 거래 제외 (ADR 007 ②④). */
@Schema(description = "면적 그룹의 월별 가격. " + BULK_EXCLUDED)
public record MonthlyPrice(
        @Schema(description = "계약월", type = "string", format = YEAR_MONTH, example = "2026-08") YearMonth month,
        @Schema(description = "계약 금액 중앙값. " + MANWON + " 짝수 건이면 가운데 두 값의 평균을 반올림(HALF_UP)",
                example = "245000")
        int medianAmount,
        @Schema(description = "유효 거래 수. " + BULK_EXCLUDED, example = "12") int count,
        @Schema(description = PROVISIONAL) boolean provisional) {
}
