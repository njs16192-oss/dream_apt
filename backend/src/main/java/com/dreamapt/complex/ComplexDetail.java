package com.dreamapt.complex;

import static com.dreamapt.common.ApiDescriptions.BULK;
import static com.dreamapt.common.ApiDescriptions.BULK_EXCLUDED;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 단지 상세. */
@Schema(description = "단지 상세")
public record ComplexDetail(
        @Schema(description = "단지 ID", example = "11710-8865") String aptSeq,
        @Schema(description = "단지명", example = "헬리오시티") String name,
        @Schema(description = "시군구 코드 (5자리)", example = "11710") String sggCd,
        @Schema(description = "시군구 이름", example = "송파구") String sggName,
        @Schema(description = "법정동 이름", example = "가락동", nullable = true) String umdName,
        @Schema(description = "준공연도", example = "2018", nullable = true) Integer buildYear,
        @Schema(description = "도로명 주소", nullable = true) String roadAddress,
        @Schema(description = "위도 (WGS84, 주출입구). 좌표가 없으면 null (ADR 010)", example = "37.499077", nullable = true)
        Double lat,
        @Schema(description = "경도 (WGS84, 주출입구). 좌표가 없으면 null", example = "127.112296", nullable = true)
        Double lng,
        @Schema(description = "유효 거래 수. " + BULK_EXCLUDED, example = "1352") int tradeCount,
        @Schema(description = "일괄 거래 수 (tradeCount에 들어 있지 않음). " + BULK, example = "0") int bulkCount,
        @Schema(description = "유효 거래 중 일괄 거래 비율 (0–1). 유효 거래가 없으면 0", example = "0") double bulkRatio,
        @Schema(description = "일괄 거래 위주 단지 (bulkRatio ≥ 0.8). true면 가격 추이 대신 안내를 보여준다")
        boolean bulkDominant,
        @Schema(description = "면적 그룹 목록, areaGroup 오름차순") List<AreaGroupSummary> areaGroups) {
}
