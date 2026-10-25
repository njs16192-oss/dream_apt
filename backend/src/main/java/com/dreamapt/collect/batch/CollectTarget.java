package com.dreamapt.collect.batch;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * 수집 단위 하나: 시군구 × 계약월.
 */
public record CollectTarget(String sggCd, YearMonth month) {

    private static final DateTimeFormatter DEAL_YM = DateTimeFormatter.ofPattern("yyyyMM");

    /** API의 DEAL_YMD 형식 (YYYYMM) */
    public String dealYm() {
        return month.format(DEAL_YM);
    }

    @Override
    public String toString() {
        return sggCd + "×" + dealYm();
    }
}
