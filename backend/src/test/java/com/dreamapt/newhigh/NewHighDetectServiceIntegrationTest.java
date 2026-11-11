package com.dreamapt.newhigh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/**
 * 신고가 판단 규칙 (ADR 011 ⑤). 기준일 2026-05-01 → 판단 범위는 계약일 2026-02-01 이후.
 * 금액은 만원 단위 (100,000 = 10억). 기본 면적 84.99㎡ = 면적 그룹 85.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NewHighDetectServiceIntegrationTest {

    private static final String APT = "11710-0001";
    private static final String OTHER_APT = "11710-0002";
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 5, 1);

    @Autowired
    NewHighDetectService service;

    @Autowired
    JdbcTemplate jdbc;

    TradeRows rows;

    @BeforeEach
    void setUp() {
        rows = new TradeRows(jdbc);
        cleanUp();
        rows.complex(APT, "헬리오시티", "11710");
        rows.complex(OTHER_APT, "파크리오", "11710");
    }

    // 다른 테스트 클래스가 apt_trade를 직접 지우므로, 기록을 남겨 두면 FK에 걸린다
    @AfterEach
    void cleanUp() {
        rows.clear();
        jdbc.update("DELETE FROM member");
    }

    @Test
    void 해제_일괄을_빼고_더_이른_계약일의_최고가를_넘은_거래만_신고가다() {
        rows.trade(APT, date(1, 10)).amount(100_000).insert(); // 1: 그룹의 첫 거래 (판단 범위 밖이지만 기준으로는 쓰임)
        long t2 = rows.trade(APT, date(2, 5)).amount(110_000).insert(); // 2: 10억 초과
        long t3 = rows.trade(APT, date(2, 5)).amount(115_000).insert(); // 3: 같은 날 2는 기준이 아님 → 10억 초과
        rows.trade(APT, date(3, 1)).amount(115_000).insert(); // 4: 최고가 11.5억과 같음 → 아님
        rows.trade(APT, date(3, 20)).amount(120_000).insert(); // 5: 일괄 (아래 9건과 함께 같은 날 10건)
        for (int i = 0; i < 9; i++) {
            // 다른 면적 그룹(60)이라 84㎡ 그룹 가격에는 영향이 없다. 일괄 판정은 단지·계약일 기준이다
            rows.trade(APT, date(3, 20)).area("59.99").amount(80_000).insert();
        }
        long t6 = rows.trade(APT, date(4, 2)).amount(118_000).insert(); // 6: 일괄 5를 빼면 최고가 11.5억 → 신고가
        rows.trade(APT, date(4, 15)).amount(130_000).cancelled().insert(); // 7: 해제
        long t8 = rows.trade(APT, date(5, 1)).amount(125_000).insert(); // 8: 해제 7을 빼면 최고가 11.8억 → 신고가

        NewHighDetectResult result = service.detect(BASE_DATE);

        assertThat(result.from()).isEqualTo(date(2, 1));
        assertThat(result.recorded()).isEqualTo(4);
        assertThat(events()).containsExactly(
                entry(t2, 100_000), entry(t3, 100_000), entry(t6, 115_000), entry(t8, 118_000));
    }

    @Test
    void 다시_돌려도_새로_기록하지_않고_처음_발견_시각을_유지한다() {
        rows.trade(APT, date(3, 1)).amount(100_000).insert();
        rows.trade(APT, date(3, 10)).amount(110_000).insert();
        service.detect(BASE_DATE);
        OffsetDateTime firstDetectedAt = jdbc.queryForObject("SELECT detected_at FROM new_high_event", OffsetDateTime.class);

        NewHighDetectResult again = service.detect(BASE_DATE);

        assertThat(again.recorded()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM new_high_event", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detected_at FROM new_high_event", OffsetDateTime.class))
                .isEqualTo(firstDetectedAt);
    }

    @Test
    void 판단_범위는_기준일_3개월_전_당일부터다() {
        rows.trade(APT, date(1, 1)).amount(100_000).insert();
        rows.trade(APT, date(1, 31)).amount(110_000).insert(); // 신고가지만 범위 밖 (하루 전)
        long onFrom = rows.trade(APT, date(2, 1)).amount(120_000).insert();

        service.detect(BASE_DATE);

        assertThat(events()).containsExactly(entry(onFrom, 110_000));
    }

    @Test
    void 이전_거래가_일괄_해제뿐이면_신고가가_아니다() {
        rows.trades(APT, date(3, 1), 10, 90_000); // 일괄
        rows.trade(APT, date(3, 5)).amount(80_000).cancelled().insert();
        rows.trade(APT, date(3, 10)).amount(100_000).insert();

        service.detect(BASE_DATE);

        assertThat(events()).isEmpty();
    }

    @Test
    void 사라진_거래는_기준도_판단_대상도_아니다() {
        rows.trade(APT, date(3, 1)).amount(100_000).insert();
        rows.trade(APT, date(3, 5)).amount(130_000).missing().insert();
        long t = rows.trade(APT, date(3, 10)).amount(120_000).insert();

        service.detect(BASE_DATE);

        assertThat(events()).containsExactly(entry(t, 100_000));
    }

    @Test
    void 면적_그룹과_단지가_다르면_비교하지_않는다() {
        rows.trade(APT, date(3, 1)).amount(100_000).insert(); // 그룹 85
        rows.trade(APT, date(3, 5)).area("84.40").amount(90_000).insert(); // 그룹 84의 첫 거래
        rows.trade(OTHER_APT, date(3, 5)).amount(90_000).insert(); // 다른 단지의 첫 거래
        rows.trade(APT, date(3, 10)).amount(95_000).insert(); // 그룹 85 최고가 10억보다 작음
        long smallerArea = rows.trade(APT, date(3, 10)).area("84.40").amount(95_000).insert();
        long otherApt = rows.trade(OTHER_APT, date(3, 10)).amount(95_000).insert();

        service.detect(BASE_DATE);

        assertThat(events()).containsExactly(entry(smallerArea, 90_000), entry(otherApt, 90_000));
    }

    @Test
    void 더_이른_날짜의_더_비싼_거래가_늦게_들어와도_기존_기록은_남고_늦은_거래도_따로_판단한다() {
        rows.trade(APT, date(3, 1)).amount(100_000).insert();
        long first = rows.trade(APT, date(3, 10)).amount(120_000).insert();
        service.detect(BASE_DATE);

        long late = rows.trade(APT, date(3, 5)).amount(150_000).insert();
        service.detect(BASE_DATE);

        assertThat(events()).containsExactly(entry(first, 100_000), entry(late, 100_000));
    }

    @Test
    void 이전_최고가가_나중에_해제되면_그다음_실행에서_새로_신고가로_잡힌다() {
        rows.trade(APT, date(3, 1)).amount(100_000).insert();
        long high = rows.trade(APT, date(3, 5)).amount(130_000).insert();
        long next = rows.trade(APT, date(3, 10)).amount(120_000).insert();
        service.detect(BASE_DATE);
        assertThat(events()).containsExactly(entry(high, 100_000));

        jdbc.update("UPDATE apt_trade SET cancelled = true WHERE id = ?", high);
        NewHighDetectResult result = service.detect(BASE_DATE);

        // 해제된 거래의 기록은 스냅샷이라 남는다 (보여줄 때 valid_apt_trade로 거른다)
        assertThat(result.recorded()).isEqualTo(1);
        assertThat(events()).containsExactly(entry(high, 100_000), entry(next, 100_000));
    }

    @Test
    void 새_신고가는_그_단지를_관심단지로_둔_회원_모두에게_알린다() {
        long a = TestMembers.insert(jdbc, "a@example.com");
        long b = TestMembers.insert(jdbc, "b@example.com");
        long otherWatcher = TestMembers.insert(jdbc, "c@example.com");
        TestMembers.insert(jdbc, "nobody@example.com"); // 관심단지 없음
        watch(a, APT);
        watch(b, APT);
        watch(otherWatcher, OTHER_APT); // 이 단지에는 신고가가 없다
        rows.trade(APT, date(3, 1)).amount(100_000).insert();
        long high = rows.trade(APT, date(3, 10)).amount(110_000).insert();
        rows.trade(OTHER_APT, date(3, 1)).amount(100_000).insert();
        rows.trade(OTHER_APT, date(3, 10)).amount(90_000).insert();

        NewHighDetectResult result = service.detect(BASE_DATE);

        assertThat(result.recorded()).isEqualTo(1);
        assertThat(result.notified()).isEqualTo(2);
        assertThat(notifications()).containsExactly(a + ":" + high, b + ":" + high);
    }

    @Test
    void 다시_돌려도_알림이_늘지_않는다() {
        long a = TestMembers.insert(jdbc, "a@example.com");
        watch(a, APT);
        rows.trade(APT, date(3, 1)).amount(100_000).insert();
        long high = rows.trade(APT, date(3, 10)).amount(110_000).insert();
        service.detect(BASE_DATE);

        NewHighDetectResult again = service.detect(BASE_DATE);

        assertThat(again.notified()).isZero();
        assertThat(notifications()).containsExactly(a + ":" + high);
    }

    @Test
    void 이미_기록된_신고가는_나중에_관심단지로_추가한_회원에게_알리지_않는다() {
        rows.trade(APT, date(3, 1)).amount(100_000).insert();
        rows.trade(APT, date(3, 10)).amount(110_000).insert();
        service.detect(BASE_DATE); // 관심 회원이 없을 때 기록됨 (로컬 DB의 PR 7 이전 기록과 같다)
        watch(TestMembers.insert(jdbc, "late@example.com"), APT);

        NewHighDetectResult again = service.detect(BASE_DATE);

        assertThat(again.notified()).isZero();
        assertThat(notifications()).isEmpty();
    }

    @Test
    void 이전_최고가가_해제된_뒤_늦게_잡힌_신고가는_그_실행에서_알린다() {
        long a = TestMembers.insert(jdbc, "a@example.com");
        watch(a, APT);
        rows.trade(APT, date(3, 1)).amount(100_000).insert();
        long high = rows.trade(APT, date(3, 5)).amount(130_000).insert();
        long next = rows.trade(APT, date(3, 10)).amount(120_000).insert();
        service.detect(BASE_DATE);

        jdbc.update("UPDATE apt_trade SET cancelled = true WHERE id = ?", high);
        NewHighDetectResult result = service.detect(BASE_DATE);

        assertThat(result.notified()).isEqualTo(1);
        assertThat(notifications()).containsExactly(a + ":" + high, a + ":" + next);
    }

    private void watch(long memberId, String aptSeq) {
        jdbc.update("INSERT INTO watchlist (member_id, apt_seq, created_at) VALUES (?, ?, now())", memberId, aptSeq);
    }

    /** "회원 id:거래 id", 회원·거래 id 순 */
    private List<String> notifications() {
        return jdbc.queryForList("""
                SELECT n.member_id || ':' || e.trade_id
                FROM notification n JOIN new_high_event e ON e.id = n.new_high_event_id
                ORDER BY n.member_id, e.trade_id
                """, String.class);
    }

    private static LocalDate date(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    /** trade_id → 이전 최고가, 거래 id 순 */
    private Map<Long, Integer> events() {
        Map<Long, Integer> events = new LinkedHashMap<>();
        jdbc.query("SELECT trade_id, previous_max_amount FROM new_high_event ORDER BY trade_id",
                rs -> {
                    events.put(rs.getLong("trade_id"), rs.getInt("previous_max_amount"));
                });
        return events;
    }
}
