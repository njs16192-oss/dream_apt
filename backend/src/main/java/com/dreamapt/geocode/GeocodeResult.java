package com.dreamapt.geocode;

import io.swagger.v3.oas.annotations.media.Schema;

/** 지오코딩 실행 한 번의 결과 요약. */
public record GeocodeResult(
        @Schema(description = "이번에 처리한 단지 수") int attempted,
        @Schema(description = "좌표를 저장한 단지 수") int succeeded,
        @Schema(description = "검색 결과 없음") int notFound,
        @Schema(description = "결과의 법정동이 단지와 다름") int mismatch,
        @Schema(description = "좌표제공 API에 좌표 없음") int noCoord,
        @Schema(description = "그 단지에만 해당하는 API 오류") int apiError,
        @Schema(description = "일시 오류. 기록하지 않아 다음 실행에서 다시 시도한다") int transientError,
        @Schema(description = "실행 후 남은 대상 수. 0이 될 때까지 다시 호출한다") long remaining) {
}
