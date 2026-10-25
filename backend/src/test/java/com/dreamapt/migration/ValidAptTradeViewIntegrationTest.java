package com.dreamapt.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.support.TestcontainersConfiguration;

/** ADR 007: valid_apt_trade 뷰의 유효 거래 조건, 면적 그룹, 일괄 거래 판정. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ValidAptTradeViewIntegrationTest {

    private static final String COMPLEX = "11710-1";
    private static final String OTHER_COMPLEX = "11500-1";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 18);

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM apt_trade");
        jdbc.update("DELETE FROM apt_complex");
        insertComplex(COMPLEX, "헬리오시티", "11710");
        insertComplex(OTHER_COMPLEX, "현대", "11500");
    }

    @Test
    void 해제되거나_사라진_거래는_뷰에_없다() {
        long valid = insertTrade(COMPLEX, DAY, "84.99", 1);
        insertTrade(COMPLEX, DAY, "84.99", 2, true, false);
        insertTrade(COMPLEX, DAY, "84.99", 3, false, true);

        assertThat(jdbc.queryForList("SELECT id FROM valid_apt_trade", Long.class)).containsExactly(valid);
    }

    @Test
    void 구_코드는_단지에서_가져온다() {
        insertTrade(COMPLEX, DAY, "84.99", 1);
        insertTrade(OTHER_COMPLEX, DAY, "84.99", 1);

        assertThat(jdbc.queryForList("SELECT apt_seq || '=' || sgg_cd FROM valid_apt_trade ORDER BY apt_seq",
                String.class)).containsExactly("11500-1=11500", "11710-1=11710");
    }

    @Test
    void 면적_그룹은_정수_반올림이고_점5는_올린다() {
        for (String area : List.of("84.49", "84.50", "84.99", "59.51")) {
            insertTrade(COMPLEX, DAY, area, 1);
        }

        assertThat(jdbc.queryForList(
                "SELECT exclu_use_ar::text || '=' || area_group FROM valid_apt_trade ORDER BY exclu_use_ar",
                String.class)).containsExactly("59.51=60", "84.49=84", "84.50=85", "84.99=85");
    }

    @Test
    void 같은_단지_같은_날_유효_거래가_10건이면_모두_일괄_거래다() {
        insertTrades(COMPLEX, DAY, 10);

        assertThat(bulkFlags()).hasSize(10).containsOnly(true);
    }

    @Test
    void 같은_날_9건이면_일괄_거래가_아니다() {
        insertTrades(COMPLEX, DAY, 9);

        assertThat(bulkFlags()).hasSize(9).containsOnly(false);
    }

    @Test
    void 해제된_거래는_일괄_거래_건수에_세지_않는다() {
        insertTrades(COMPLEX, DAY, 9);
        insertTrade(COMPLEX, DAY, "84.99", 10, true, false);

        assertThat(bulkFlags()).hasSize(9).containsOnly(false);
    }

    @Test
    void 다른_날이나_다른_단지의_거래는_합쳐_세지_않는다() {
        insertTrades(COMPLEX, DAY, 5);
        insertTrades(COMPLEX, DAY.plusDays(1), 5);
        insertTrades(OTHER_COMPLEX, DAY, 5);

        assertThat(bulkFlags()).hasSize(15).containsOnly(false);
    }

    @Test
    void 구로_걸러도_일괄_거래_판정은_같다() {
        insertTrades(COMPLEX, DAY, 10);
        insertTrades(OTHER_COMPLEX, DAY, 3);

        assertThat(jdbc.queryForList("SELECT is_bulk FROM valid_apt_trade WHERE sgg_cd = '11710'", Boolean.class))
                .hasSize(10).containsOnly(true);
        assertThat(jdbc.queryForList("SELECT is_bulk FROM valid_apt_trade WHERE sgg_cd = '11500'", Boolean.class))
                .hasSize(3).containsOnly(false);
    }

    @Test
    void 단지명은_trigram으로_띄어쓰기가_달라도_찾는다() {
        assertThat(jdbc.queryForList("SELECT apt_seq FROM apt_complex WHERE name % '헬리오 시티'", String.class))
                .containsExactly(COMPLEX);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_complex_name_trgm'", Integer.class))
                .isEqualTo(1);
    }

    private List<Boolean> bulkFlags() {
        return jdbc.queryForList("SELECT is_bulk FROM valid_apt_trade", Boolean.class);
    }

    private void insertTrades(String aptSeq, LocalDate day, int count) {
        for (int i = 1; i <= count; i++) {
            insertTrade(aptSeq, day, "84.99", i);
        }
    }

    private long insertTrade(String aptSeq, LocalDate day, String area, int floor) {
        return insertTrade(aptSeq, day, area, floor, false, false);
    }

    private long insertTrade(String aptSeq, LocalDate day, String area, int floor, boolean cancelled,
            boolean missing) {
        return jdbc.queryForObject("""
                INSERT INTO apt_trade (apt_seq, deal_date, deal_amount, exclu_use_ar, floor, cancelled, missing_since)
                VALUES (?, ?, 100000, ?, ?, ?, CASE WHEN ? THEN now() END)
                RETURNING id
                """, Long.class, aptSeq, day, new BigDecimal(area), floor, cancelled, missing);
    }

    private void insertComplex(String aptSeq, String name, String sggCd) {
        jdbc.update("INSERT INTO apt_complex (apt_seq, name, sgg_cd, umd_cd) VALUES (?, ?, ?, '10100')",
                aptSeq, name, sggCd);
    }
}
