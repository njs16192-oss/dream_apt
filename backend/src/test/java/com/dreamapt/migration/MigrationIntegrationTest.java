package com.dreamapt.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.region.RegionRepository;
import com.dreamapt.support.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MigrationIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RegionRepository regionRepository;

    @Test
    void 시각_컬럼은_모두_TIMESTAMPTZ다() {
        List<String> types = jdbc.queryForList("""
                SELECT table_name || '.' || column_name || '=' || data_type FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('apt_complex', 'apt_trade', 'collect_history')
                  AND data_type LIKE 'timestamp%'
                ORDER BY 1
                """, String.class);

        assertThat(types).containsExactly(
                "apt_complex.created_at=timestamp with time zone",
                "apt_complex.geocode_attempted_at=timestamp with time zone",
                "apt_complex.updated_at=timestamp with time zone",
                "apt_trade.created_at=timestamp with time zone",
                "apt_trade.missing_since=timestamp with time zone",
                "apt_trade.updated_at=timestamp with time zone",
                "collect_history.last_attempted_at=timestamp with time zone",
                "collect_history.last_succeeded_at=timestamp with time zone");
    }

    @Test
    void 수집_대상은_서울_25개_구다() {
        assertThat(regionRepository.findByCollectEnabledTrueOrderBySggCd())
                .hasSize(25)
                .allSatisfy(r -> assertThat(r.getSggCd()).startsWith("11"))
                .first().satisfies(r -> assertThat(r.getSggNm()).isEqualTo("종로구"));
    }

    @Test
    void Spring_Batch_메타데이터_테이블이_있다() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name LIKE 'batch\\_%'
                """, Integer.class)).isEqualTo(6);
    }
}
