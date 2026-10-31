package com.dreamapt.geocode;

/** 지오코딩 실패 사유. apt_complex.geocode_error에 이름 그대로 저장한다 (ADR 010). */
public enum GeocodeError {
    /** 도로명·지번 검색 모두 결과가 없다 */
    NOT_FOUND,
    /** 결과는 있지만 단지 주소와 다르다 (법정동, 또는 건물번호·번지). 다른 건물을 고르지 않으려고 버린다 */
    MISMATCH,
    /** 건물은 찾았지만 좌표제공 API에 좌표가 없다 */
    NO_COORD,
    /** 검색어 형식 오류 등 그 단지에만 해당하는 API 오류 */
    API_ERROR
}
