package com.dreamapt.common;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 월별 응답의 공통 형태. 거래가 없는 달은 months에 넣지 않는다.
 *
 * @param baseDate        기준일 (서울 날짜)
 * @param provisionalFrom 이 달부터는 집계 중 (provisional = true)
 * @param months          월 오름차순
 */
public record MonthlySeries<T>(LocalDate baseDate, YearMonth provisionalFrom, List<T> months) {

    public static <T> MonthlySeries<T> of(ReportingPeriod period, List<T> months) {
        return new MonthlySeries<>(period.baseDate(), period.provisionalFrom(), months);
    }
}
