package com.dreamapt.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.common.ConflictException;
import com.dreamapt.common.NotFoundException;
import com.dreamapt.support.TestMembers;
import com.dreamapt.support.TestcontainersConfiguration;
import com.dreamapt.support.TradeRows;

/** 관심단지 추가·해제 규칙 (ADR 011 ⑥: 단지 단위, 회원당 최대 50개) */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WatchlistServiceIntegrationTest {

    private static final int MAX = WatchlistService.MAX_WATCHLIST;

    @Autowired
    WatchlistService service;

    @Autowired
    JdbcTemplate jdbc;

    TradeRows rows;
    long me;
    long other;

    @BeforeEach
    void setUp() {
        rows = new TradeRows(jdbc);
        cleanUp();
        // 50개를 채우고도 하나 더 넣을 수 있게 51개
        for (int i = 1; i <= MAX + 1; i++) {
            rows.complex(apt(i), "단지" + i, "11710");
        }
        me = TestMembers.insert(jdbc, "me@example.com");
        other = TestMembers.insert(jdbc, "other@example.com");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM member");
        rows.clear();
    }

    @Test
    void 제한은_50개다() {
        assertThat(MAX).isEqualTo(50);
    }

    @Test
    void 개수가_49개면_하나_더_추가해_50개가_된다() {
        fill(me, MAX - 1);

        service.add(me, apt(MAX));

        assertThat(count(me)).isEqualTo(MAX);
    }

    @Test
    void 개수가_50개면_새_단지는_409이고_개수는_그대로다() {
        fill(me, MAX);

        assertThatThrownBy(() -> service.add(me, apt(MAX + 1)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("관심단지는 최대 50개까지입니다");
        assertThat(count(me)).isEqualTo(MAX);
    }

    @Test
    void 개수가_50개여도_이미_있는_단지를_다시_추가하면_성공한다() {
        fill(me, MAX);

        service.add(me, apt(1));

        assertThat(count(me)).isEqualTo(MAX);
    }

    @Test
    void 제한은_회원별이다() {
        fill(other, MAX);

        service.add(me, apt(1));

        assertThat(count(me)).isEqualTo(1);
    }

    @Test
    void 같은_단지를_두_번_추가하면_행은_하나이고_추가_시각도_그대로다() {
        service.add(me, apt(1));
        OffsetDateTime first = addedAt(me, apt(1));

        service.add(me, apt(1));

        assertThat(count(me)).isEqualTo(1);
        assertThat(addedAt(me, apt(1))).isEqualTo(first);
    }

    @Test
    void 없는_단지는_404이고_추가되지_않는다() {
        assertThatThrownBy(() -> service.add(me, "99999-1"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("단지를 찾을 수 없습니다");
        assertThat(count(me)).isZero();
    }

    @Test
    void 다른_회원이_같은_단지를_해제해도_내_관심단지는_남는다() {
        service.add(me, apt(1));
        service.add(other, apt(1));

        service.remove(other, apt(1));

        assertThat(service.findAll(me)).extracting(WatchlistItem::aptSeq).containsExactly(apt(1));
        assertThat(service.findAll(other)).isEmpty();
    }

    @Test
    void 목록에_없는_단지를_해제해도_오류가_없다() {
        service.add(me, apt(1));

        service.remove(me, apt(2));
        service.remove(me, "99999-1");

        assertThat(count(me)).isEqualTo(1);
    }

    private static String apt(int i) {
        return "11710-" + i;
    }

    /** 1번부터 n번 단지까지 넣는다 */
    private void fill(long memberId, int n) {
        for (int i = 1; i <= n; i++) {
            service.add(memberId, apt(i));
        }
        assertThat(count(memberId)).isEqualTo(n);
    }

    private int count(long memberId) {
        return jdbc.queryForObject("SELECT count(*) FROM watchlist WHERE member_id = ?", Integer.class, memberId);
    }

    private OffsetDateTime addedAt(long memberId, String aptSeq) {
        return jdbc.queryForObject("SELECT created_at FROM watchlist WHERE member_id = ? AND apt_seq = ?",
                OffsetDateTime.class, memberId, aptSeq);
    }
}
