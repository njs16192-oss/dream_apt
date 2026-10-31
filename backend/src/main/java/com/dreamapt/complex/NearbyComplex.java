package com.dreamapt.complex;

import static com.dreamapt.common.ApiDescriptions.BULK_EXCLUDED;

import io.swagger.v3.oas.annotations.media.Schema;

/** 반경 검색 결과 한 건. */
@Schema(description = "반경 안의 단지")
public record NearbyComplex(
        @Schema(description = "단지 ID", example = "11710-8865") String aptSeq,
        @Schema(description = "단지명", example = "헬리오시티") String name,
        @Schema(description = "시군구 이름", example = "송파구") String sggName,
        @Schema(description = "법정동 이름", example = "가락동", nullable = true) String umdName,
        @Schema(description = "준공연도", example = "2018", nullable = true) Integer buildYear,
        @Schema(description = "위도 (WGS84). 주출입구 좌표", example = "37.499077") double lat,
        @Schema(description = "경도 (WGS84). 주출입구 좌표", example = "127.112296") double lng,
        @Schema(description = "중심에서의 거리 (m, 반올림)", example = "320") int distanceM,
        @Schema(description = "기준일 전 12개월 유효 거래 수. " + BULK_EXCLUDED, example = "152") int recentTradeCount) {
}
