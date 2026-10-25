package com.dreamapt.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 조회 기준일과 "집계 중" 월 (ADR 007 ⑤).
 * 기준일(서울 날짜)이 속한 달과 그 전 달은 신고·해제가 더 반영될 수 있어 provisional로 표시한다.
 *
 * @param baseDate        기준일
 * @param provisionalFrom 이 달부터 기준일이 속한 달까지가 집계 중
 */
public record ReportingPeriod(LocalDate baseDate, YearMonth provisionalFrom) {

    /** 집계 중으로 표시하는 최근 개월 수 (기준일이 속한 달 포함) */
    public static final int PROVISIONAL_MONTHS = 2;

    public static ReportingPeriod of(Clock clock) {
        return of(LocalDate.now(clock));
    }

    public static ReportingPeriod of(LocalDate baseDate) {
        return new ReportingPeriod(baseDate, YearMonth.from(baseDate).minusMonths(PROVISIONAL_MONTHS - 1));
    }

    public YearMonth baseMonth() {
        return YearMonth.from(baseDate);
    }

    public boolean isProvisional(YearMonth month) {
        return !month.isBefore(provisionalFrom);
    }
}
