package com.dreamapt.common;

import java.util.List;

/**
 * 오프셋 페이지 응답.
 *
 * @param page          0부터 시작하는 페이지 번호
 * @param size          페이지 크기
 * @param totalElements 전체 건수
 * @param totalPages    전체 페이지 수
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
