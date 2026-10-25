package com.dreamapt.region;

import io.swagger.v3.oas.annotations.media.Schema;

/** 구 목록 한 건 (검색·필터용). */
@Schema(description = "시군구")
public record RegionItem(
        @Schema(description = "시군구 코드 (5자리)", example = "11710") String sggCd,
        @Schema(description = "시도 이름", example = "서울특별시") String sidoName,
        @Schema(description = "시군구 이름", example = "송파구") String sggName) {

    static RegionItem from(Region region) {
        return new RegionItem(region.getSggCd(), region.getSidoNm(), region.getSggNm());
    }
}
