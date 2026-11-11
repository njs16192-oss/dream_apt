package com.dreamapt.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.support.TestMembers;
import com.dreamapt.support.TestcontainersConfiguration;
import com.dreamapt.support.TradeRows;

/** 관심단지 SQL을 실제 DB(PostGIS 컨테이너)로 확인한다 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WatchlistRepositoryIntegrationTest {

    private static final String HELIO = "11710-8865";
    private static final String PARK = "11710-1";
    private static final String RAEMIAN = "11680-1";

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
        rows.complex(RAEMIAN, "래미안", "11680");
        me = TestMembers.insert(jdbc, "me@example.com");
        other = TestMembers.insert(jdbc, "other@example.com");
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

    @Test
    void 목록은_최근에_추가한_순서이고_단지명과_구_이름을_준다() {
        addAt(me, HELIO, "2026-11-01T10:00:00+09:00");
        addAt(me, RAEMIAN, "2026-11-03T10:00:00+09:00");
        addAt(me, PARK, "2026-11-02T10:00:00+09:00");

        List<WatchlistItem> items = repository.findAll(me);

        assertThat(items).extracting(WatchlistItem::aptSeq, WatchlistItem::name, WatchlistItem::sggCd,
                WatchlistItem::sggName)
                .containsExactly(
                        tuple(RAEMIAN, "래미안", "11680", "강남구"),
                        tuple(PARK, "파크리오", "11710", "송파구"),
                        tuple(HELIO, "헬리오시티", "11710", "송파구"));
        assertThat(items.get(0).addedAt()).isEqualTo(OffsetDateTime.parse("2026-11-03T10:00:00+09:00"));
    }

    @Test
    void 추가_시각이_같으면_단지_ID_순이다() {
        addAt(me, PARK, "2026-11-01T10:00:00+09:00");
        addAt(me, HELIO, "2026-11-01T10:00:00+09:00");

        assertThat(repository.findAll(me)).extracting(WatchlistItem::aptSeq).containsExactly(PARK, HELIO);
    }

    @Test
    void 최근_거래일은_유효하고_일괄이_아닌_거래_중_가장_늦은_계약일이다() {
        rows.trade(HELIO, LocalDate.of(2026, 9, 1)).insert();
        rows.trade(HELIO, LocalDate.of(2026, 10, 1)).insert();
        rows.trade(HELIO, LocalDate.of(2026, 10, 20)).cancelled().insert();
        rows.trade(HELIO, LocalDate.of(2026, 10, 21)).missing().insert();
        rows.trades(HELIO, LocalDate.of(2026, 10, 22), 10, 90_000); // 같은 날 10건 → 일괄 거래
        repository.add(me, HELIO);

        assertThat(repository.findAll(me)).singleElement()
                .extracting(WatchlistItem::lastDealDate).isEqualTo(LocalDate.of(2026, 10, 1));
    }

    @Test
    void 거래가_없는_단지도_목록에_나오고_최근_거래일은_null이다() {
        rows.trade(PARK, LocalDate.of(2026, 10, 1)).cancelled().insert(); // 해제 거래만 있는 단지
        addAt(me, HELIO, "2026-11-01T10:00:00+09:00");
        addAt(me, PARK, "2026-11-02T10:00:00+09:00");

        assertThat(repository.findAll(me))
                .extracting(WatchlistItem::aptSeq, WatchlistItem::lastDealDate)
                .containsExactly(tuple(PARK, null), tuple(HELIO, null));
    }

    @Test
    void 목록에_다른_회원의_관심단지는_나오지_않는다() {
        repository.add(me, HELIO);
        repository.add(other, PARK);

        assertThat(repository.findAll(me)).extracting(WatchlistItem::aptSeq).containsExactly(HELIO);
        assertThat(repository.findAll(TestMembers.insert(jdbc, "empty@example.com"))).isEmpty();
    }

    private void addAt(long memberId, String aptSeq, String createdAt) {
        repository.add(memberId, aptSeq);
        jdbc.update("UPDATE watchlist SET created_at = ?::timestamptz WHERE member_id = ? AND apt_seq = ?",
                createdAt, memberId, aptSeq);
    }
}
