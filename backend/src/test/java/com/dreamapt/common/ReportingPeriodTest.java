package com.dreamapt.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class ReportingPeriodTest {

    @Test
    void 기준일이_속한_달과_그_전_달이_집계_중이다() {
        ReportingPeriod period = ReportingPeriod.of(LocalDate.of(2026, 10, 25));

        assertThat(period.provisionalFrom()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(period.isProvisional(YearMonth.of(2026, 8))).isFalse();
        assertThat(period.isProvisional(YearMonth.of(2026, 9))).isTrue();
        assertThat(period.isProvisional(YearMonth.of(2026, 10))).isTrue();
    }

    @Test
    void 기준일이_1월이면_전년_12월부터_집계_중이다() {
        assertThat(ReportingPeriod.of(LocalDate.of(2027, 1, 1)).provisionalFrom()).isEqualTo(YearMonth.of(2026, 12));
    }
}
