package com.dreamapt.region;

import static com.dreamapt.common.ApiDescriptions.BULK;
import static com.dreamapt.common.ApiDescriptions.BULK_EXCLUDED;
import static com.dreamapt.common.ApiDescriptions.PROVISIONAL;
import static com.dreamapt.common.ApiDescriptions.YEAR_MONTH;

import java.time.YearMonth;

import io.swagger.v3.oas.annotations.media.Schema;

/** 구의 월별 거래량 (ADR 007 ②). */
@Schema(description = "구의 월별 거래량. " + BULK_EXCLUDED + " 일괄 거래 수는 bulkCount로 따로 준다")
public record MonthlyVolume(
        @Schema(description = "계약 월", type = "string", format = YEAR_MONTH, example = "2026-08") YearMonth month,
        @Schema(description = "유효 거래 수. " + BULK_EXCLUDED, example = "412") int count,
        @Schema(description = "일괄 거래 수 (count에 들어 있지 않음). " + BULK, example = "0") int bulkCount,
        @Schema(description = PROVISIONAL) boolean provisional) {
}
