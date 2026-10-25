package com.dreamapt.complex;

import static com.dreamapt.common.ApiDescriptions.BULK;
import static com.dreamapt.common.ApiDescriptions.MANWON;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

/** 거래 목록 한 건. 거래 목록은 일괄 거래도 포함하고 bulk로 표시한다 (ADR 007 ②). */
@Schema(description = "거래 한 건. 거래 목록은 일괄 거래도 포함하고 bulk로 표시한다")
public record TradeItem(
        @Schema(description = "거래 ID") long id,
        @Schema(description = "계약일", example = "2026-09-18") LocalDate dealDate,
        @Schema(description = "거래 금액. " + MANWON, example = "245000") int dealAmount,
        @Schema(description = "전용면적 원값(㎡)", example = "84.99") BigDecimal exclusiveArea,
        @Schema(description = "면적 그룹: 전용면적(㎡)을 정수로 반올림한 값", example = "85") int areaGroup,
        @Schema(description = "층", example = "12") int floor,
        @Schema(description = "동. 나중에 채워지는 값이라 비어 있을 수 있다", nullable = true) String aptDong,
        @Schema(description = "거래 유형 (중개거래/직거래). 2021-11 이전 계약은 null", nullable = true) String dealingGbn,
        @Schema(description = "매수자 구분 (개인/법인/공공기관/기타). 2024 이전 계약은 null", nullable = true) String buyerGbn,
        @Schema(description = "매도자 구분 (개인/법인/공공기관/기타). 2024 이전 계약은 null", nullable = true) String sellerGbn,
        @Schema(description = "등기일. 아직 등기되지 않았으면 null", nullable = true) LocalDate registeredDate,
        @Schema(description = "일괄 거래 여부. " + BULK) boolean bulk) {
}
