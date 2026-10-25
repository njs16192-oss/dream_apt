package com.dreamapt.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.common.BadRequestException;
import com.dreamapt.common.MonthlySeries;
import com.dreamapt.common.NotFoundException;
import com.dreamapt.support.TestcontainersConfiguration;
import com.dreamapt.support.TradeRows;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RegionQueryServiceIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, SEOUL).toInstant(), SEOUL);

    @Autowired
    RegionRepository regionRepository;

    @Autowired
    RegionQueryRepository queryRepository;

    @Autowired
    JdbcTemplate jdbc;

    TradeRows rows;
    RegionQueryService service;

    @BeforeEach
    void setUp() {
        rows = new TradeRows(jdbc);
        rows.clear();
        rows.complex("11710-1", "헬리오시티", "11710");
        rows.complex("11710-2", "송파해링턴타워", "11710");
        rows.complex("11500-1", "현대", "11500");
        service = new RegionQueryService(regionRepository, queryRepository, CLOCK);
    }

    @Test
    void 월별_거래량은_일괄_거래를_빼고_일괄_거래_수는_따로_준다() {
        rows.trades("11710-2", LocalDate.of(2026, 8, 18), 10, 50_000);
        rows.trade("11710-1", LocalDate.of(2026, 8, 18)).insert();
        rows.trade("11710-1", LocalDate.of(2026, 8, 20)).insert();
        rows.trade("11710-1", LocalDate.of(2026, 8, 21)).cancelled().insert();
        rows.trade("11500-1", LocalDate.of(2026, 8, 18)).insert();

        assertThat(service.getMonthlyVolumes("11710", null, null).months()).containsExactly(
                new MonthlyVolume(YearMonth.of(2026, 8), 2, 10, false));
    }

    @Test
    void 기준일이_속한_달과_그_전_달은_집계_중이다() {
        rows.trade("11710-1", LocalDate.of(2026, 8, 31)).insert();
        rows.trade("11710-1", LocalDate.of(2026, 9, 1)).insert();
        rows.trade("11710-1", LocalDate.of(2026, 10, 1)).insert();

        MonthlySeries<MonthlyVolume> series = service.getMonthlyVolumes("11710", null, null);

        assertThat(series.provisionalFrom()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(series.months()).extracting(MonthlyVolume::provisional).containsExactly(false, true, true);
    }

    @Test
    void from과_to는_양쪽_달을_포함한다() {
        rows.trade("11710-1", LocalDate.of(2026, 6, 30)).insert();
        rows.trade("11710-1", LocalDate.of(2026, 7, 1)).insert();
        rows.trade("11710-1", LocalDate.of(2026, 7, 31)).insert();
        rows.trade("11710-1", LocalDate.of(2026, 8, 1)).insert();

        assertThat(service.getMonthlyVolumes("11710", YearMonth.of(2026, 7), YearMonth.of(2026, 7)).months())
                .containsExactly(new MonthlyVolume(YearMonth.of(2026, 7), 2, 0, false));
    }

    @Test
    void from을_생략하면_to를_포함한_최근_36개월이다() {
        rows.trade("11710-1", LocalDate.of(2023, 10, 31)).insert();
        rows.trade("11710-1", LocalDate.of(2023, 11, 1)).insert();

        MonthlySeries<MonthlyVolume> series = service.getMonthlyVolumes("11710", null, null);

        assertThat(series.from()).isEqualTo(YearMonth.of(2023, 11));
        assertThat(series.to()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(series.months()).extracting(MonthlyVolume::month).containsExactly(YearMonth.of(2023, 11));
    }

    @Test
    void to만_주면_그_달까지_36개월이다() {
        MonthlySeries<MonthlyVolume> series = service.getMonthlyVolumes("11710", null, YearMonth.of(2025, 12));

        assertThat(series.from()).isEqualTo(YearMonth.of(2023, 1));
        assertThat(series.to()).isEqualTo(YearMonth.of(2025, 12));
    }

    @Test
    void 기간은_최대_120개월이다() {
        assertThat(service.getMonthlyVolumes("11710", YearMonth.of(2016, 11), YearMonth.of(2026, 10)).from())
                .isEqualTo(YearMonth.of(2016, 11));
        assertThatThrownBy(() -> service.getMonthlyVolumes("11710", YearMonth.of(2016, 10), YearMonth.of(2026, 10)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("121개월");
    }

    @Test
    void 잘못된_기간이나_없는_구면_예외다() {
        assertThatThrownBy(() -> service.getMonthlyVolumes("11710", YearMonth.of(2026, 9), YearMonth.of(2026, 8)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.getMonthlyVolumes("99999", null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void 구_목록은_수집_대상_구다() {
        assertThat(service.listRegions()).hasSize(25).first()
                .isEqualTo(new RegionItem("11110", "서울특별시", "종로구"));
    }
}
