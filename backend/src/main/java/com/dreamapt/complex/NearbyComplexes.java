package com.dreamapt.complex;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 반경 검색 결과. 반경 안 단지가 limit보다 많으면 가까운 순으로 limit개만 준다 (ADR 010 ⑧). */
@Schema(description = "반경 검색 결과")
public record NearbyComplexes(
        @Schema(description = "중심 위도", example = "37.499077") double lat,
        @Schema(description = "중심 경도", example = "127.112296") double lng,
        @Schema(description = "반경 (m)", example = "1000") int radius,
        @Schema(description = "반경 안의 전체 단지 수 (좌표 없는 단지 제외). items보다 많으면 잘린 것이다", example = "51")
        int totalCount,
        @Schema(description = "가까운 순 (같은 거리면 단지 ID 순), 최대 limit개") List<NearbyComplex> items) {
}
