package com.dreamapt.region;

/** 구 목록 한 건 (검색·필터용). */
public record RegionItem(String sggCd, String sidoName, String sggName) {

    static RegionItem from(Region region) {
        return new RegionItem(region.getSggCd(), region.getSidoNm(), region.getSggNm());
    }
}
