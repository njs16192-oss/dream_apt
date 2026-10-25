package com.dreamapt.collect.batch;

/**
 * 수집 모드 (ADR 005). Job 파라미터 mode에는 소문자 이름을 쓴다.
 */
public enum CollectMode {
    /** 최근 6개월 (이번 달 포함) */
    DAILY,
    /** 7–12개월 전 */
    WEEKLY,
    /** 시작 월부터 이번 달까지. 이미 성공한 구×월은 건너뛴다 */
    BACKFILL;

    public static CollectMode from(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("mode는 daily, weekly, backfill 중 하나: " + value, e);
        }
    }

    public String parameterValue() {
        return name().toLowerCase();
    }
}
