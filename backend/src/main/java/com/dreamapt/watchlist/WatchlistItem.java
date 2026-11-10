package com.dreamapt.watchlist;

import static com.dreamapt.common.ApiDescriptions.BULK_EXCLUDED;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/** 관심단지 목록 한 건 */
@Schema(description = "관심단지")
public record WatchlistItem(
        @Schema(description = "단지 ID", example = "11710-8865") String aptSeq,
        @Schema(description = "단지명", example = "헬리오시티") String name,
        @Schema(description = "시군구 코드 (5자리)", example = "11710") String sggCd,
        @Schema(description = "시군구 이름", example = "송파구") String sggName,
        @Schema(description = "가장 최근 유효 거래의 계약일. " + BULK_EXCLUDED + " 거래가 없으면 null",
                example = "2026-10-12", nullable = true) LocalDate lastDealDate,
        @Schema(description = "관심단지에 추가한 시각") OffsetDateTime addedAt) {
}
