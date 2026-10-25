package com.dreamapt.common;

import static com.dreamapt.common.ApiDescriptions.YEAR_MONTH;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 월별 응답의 공통 형태. 거래가 없는 달은 months에 넣지 않는다. */
@Schema(description = "월별 응답. 거래가 없는 달은 months에 없다")
public record MonthlySeries<T>(
        @Schema(description = "기준일 (서울 날짜)", example = "2026-10-25") LocalDate baseDate,
        @Schema(description = "이 달부터 기준일이 속한 달까지는 집계 중(provisional = true)", type = "string",
                format = YEAR_MONTH, example = "2026-09")
        YearMonth provisionalFrom,
        @Schema(description = "실제로 적용한 시작 월 (포함). null이면 처음부터", type = "string", format = YEAR_MONTH,
                example = "2023-11", nullable = true)
        YearMonth from,
        @Schema(description = "실제로 적용한 끝 월 (포함)", type = "string", format = YEAR_MONTH, example = "2026-10")
        YearMonth to,
        @Schema(description = "월 오름차순") List<T> months) {

    public static <T> MonthlySeries<T> of(ReportingPeriod period, YearMonth from, YearMonth to, List<T> months) {
        return new MonthlySeries<>(period.baseDate(), period.provisionalFrom(), from, to, months);
    }
}
