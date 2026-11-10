package com.dreamapt.watchlist;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.support.TestcontainersConfiguration;
import com.dreamapt.support.TradeRows;

/** 관심단지 SQL을 실제 DB(PostGIS 컨테이너)로 확인한다 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WatchlistRepositoryIntegrationTest {

    private static final String HELIO = "11710-8865";
    private static final String PARK = "11710-1";

    @Autowired
    WatchlistRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    TradeRows rows;
    long me;
    long other;

    @BeforeEach
    void setUp() {
        rows = new TradeRows(jdbc);
        cleanUp();
        rows.complex(HELIO, "헬리오시티", "11710");
        rows.complex(PARK, "파크리오", "11710");
        me = WatchlistTestMembers.insert(jdbc, "me@example.com");
        other = WatchlistTestMembers.insert(jdbc, "other@example.com");
    }

    /** 다른 테스트가 apt_complex를 지울 때 FK에 걸리지 않게 관심단지·회원을 남기지 않는다 */
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM member");
        rows.clear();
    }

    @Test
    void 추가한_단지만_있다고_답한다() {
        repository.add(me, HELIO);

        assertThat(repository.exists(me, HELIO)).isTrue();
        assertThat(repository.exists(me, PARK)).isFalse();
        assertThat(repository.exists(other, HELIO)).isFalse();
    }

    @Test
    void 같은_단지를_두_번_추가해도_행은_하나다() {
        repository.add(me, HELIO);
        repository.add(me, HELIO);

        assertThat(repository.count(me)).isEqualTo(1);
    }

    @Test
    void 개수는_회원별로_센다() {
        repository.add(me, HELIO);
        repository.add(me, PARK);
        repository.add(other, HELIO);

        assertThat(repository.count(me)).isEqualTo(2);
        assertThat(repository.count(other)).isEqualTo(1);
    }

    @Test
    void 해제는_내_행만_지운다() {
        repository.add(me, HELIO);
        repository.add(other, HELIO);

        repository.remove(other, HELIO);

        assertThat(repository.exists(me, HELIO)).isTrue();
        assertThat(repository.exists(other, HELIO)).isFalse();
    }
}
