package com.dreamapt.complex;

import static com.dreamapt.common.ApiDescriptions.BULK_EXCLUDED;

import io.swagger.v3.oas.annotations.media.Schema;

/** 단지 검색 결과 한 건. 같은 이름 단지가 많아(19.4%) 구·법정동·준공연도를 함께 준다 (ADR 007 ⑥). */
@Schema(description = "단지 검색 결과")
public record ComplexSearchItem(
        @Schema(description = "단지 ID", example = "11710-8865") String aptSeq,
        @Schema(description = "단지명", example = "헬리오시티") String name,
        @Schema(description = "시군구 코드 (5자리)", example = "11710") String sggCd,
        @Schema(description = "시군구 이름", example = "송파구") String sggName,
        @Schema(description = "법정동 이름", example = "가락동", nullable = true) String umdName,
        @Schema(description = "준공연도", example = "2018", nullable = true) Integer buildYear,
        @Schema(description = "기준일 전 12개월 유효 거래 수. " + BULK_EXCLUDED + " 검색 정렬 기준이다", example = "152")
        int recentTradeCount) {
}
