package com.dreamapt.common;

/** API 문서(@Schema·@Operation)에 반복해서 쓰는 설명. 규칙이 바뀌면 여기만 고친다 (ADR 007). */
public final class ApiDescriptions {

    public static final String MANWON = "단위: 만원.";

    public static final String BULK_EXCLUDED = "일괄 거래 제외.";

    public static final String BULK = "일괄 거래: 같은 단지·같은 계약일 유효 거래가 10건 이상인 거래.";

    public static final String PROVISIONAL = "집계 중인 달(기준일이 속한 달과 그 전 달). "
            + "신고(계약 후 30일 안)·해제가 더 반영되어 값이 바뀔 수 있다.";

    public static final String YEAR_MONTH = "yyyy-MM";

    private ApiDescriptions() {
    }
}
