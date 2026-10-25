package com.dreamapt.common;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** 오프셋 페이지 응답. */
@Schema(description = "페이지 응답")
public record PageResponse<T>(
        @Schema(description = "이 페이지의 항목") List<T> content,
        @Schema(description = "페이지 번호 (0부터)", example = "0") int page,
        @Schema(description = "페이지 크기", example = "20") int size,
        @Schema(description = "전체 건수", example = "1352") long totalElements,
        @Schema(description = "전체 페이지 수", example = "68") int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
