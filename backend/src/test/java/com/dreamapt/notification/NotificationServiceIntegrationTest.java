package com.dreamapt.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.dreamapt.common.NotFoundException;
import com.dreamapt.common.PageResponse;
import com.dreamapt.support.TestMembers;
import com.dreamapt.support.TestcontainersConfiguration;
import com.dreamapt.support.TradeRows;

/**
 * 알림 목록·안 읽음 수·읽음 처리 (ADR 011 ⑥). 알림 생성은 NewHighDetectServiceIntegrationTest에서 본다.
 * 여기서는 신고가 기록·알림 행을 직접 넣는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationServiceIntegrationTest {

    private static final String APT = "11710-0001";
    private static final LocalDate DEAL_DATE = LocalDate.of(2026, 3, 10);
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-03-20T00:00:00Z");

    @Autowired
    NotificationService service;

    @Autowired
    JdbcTemplate jdbc;

    TradeRows rows;
    long me;
    long other;

    @BeforeEach
    void setUp() {
        rows = new TradeRows(jdbc);
        cleanUp();
        rows.complex(APT, "헬리오시티", "11710");
        me = TestMembers.insert(jdbc, "me@example.com");
        other = TestMembers.insert(jdbc, "other@example.com");
    }

    @AfterEach
    void cleanUp() {
        rows.clear();
        jdbc.update("DELETE FROM member");
    }

    @Test
    void 목록은_신고가_거래_정보를_최신순으로_페이지로_준다() {
        long trade = rows.trade(APT, DEAL_DATE).amount(120_000).area("84.40").insert();
        long event = event(trade, 110_000);
        long older = notify(me, event, T0);
        long newer = notify(me, event(rows.trade(APT, DEAL_DATE.plusDays(1)).insert(), 90_000), T0.plusHours(1));
        long newest = notify(me, event(rows.trade(APT, DEAL_DATE.plusDays(2)).insert(), 90_000), T0.plusHours(2));

        PageResponse<NotificationItem> first = service.findPage(me, 0, 2);
        PageResponse<NotificationItem> second = service.findPage(me, 1, 2);

        assertThat(first.content()).extracting(NotificationItem::id).containsExactly(newest, newer);
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(second.content()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(older);
            assertThat(item.aptSeq()).isEqualTo(APT);
            assertThat(item.complexName()).isEqualTo("헬리오시티");
            assertThat(item.areaGroup()).isEqualTo(84);
            assertThat(item.exclusiveArea()).isEqualByComparingTo(new BigDecimal("84.40"));
            assertThat(item.dealDate()).isEqualTo(DEAL_DATE);
            assertThat(item.dealAmount()).isEqualTo(120_000);
            assertThat(item.previousMaxAmount()).isEqualTo(110_000);
            assertThat(item.createdAt()).isEqualTo(T0);
            assertThat(item.read()).isFalse();
        });
    }

    @Test
    void 같은_시각이면_id_내림차순이다() {
        long a = notify(me, event(rows.trade(APT, DEAL_DATE).insert(), 90_000), T0);
        long b = notify(me, event(rows.trade(APT, DEAL_DATE.plusDays(1)).insert(), 90_000), T0);

        assertThat(service.findPage(me, 0, 20).content()).extracting(NotificationItem::id).containsExactly(b, a);
    }

    @Test
    void 지금은_해제_사라짐_일괄이_된_거래의_알림은_목록과_개수에서_빠진다() {
        long visible = notify(me, event(rows.trade(APT, DEAL_DATE).insert(), 90_000), T0);
        long cancelled = rows.trade(APT, DEAL_DATE.plusDays(1)).insert();
        long missing = rows.trade(APT, DEAL_DATE.plusDays(2)).insert();
        long bulk = rows.trade(APT, DEAL_DATE.plusDays(3)).insert();
        notify(me, event(cancelled, 90_000), T0);
        notify(me, event(missing, 90_000), T0);
        notify(me, event(bulk, 90_000), T0);

        // 알림을 만든 뒤에 거래가 바뀐다
        jdbc.update("UPDATE apt_trade SET cancelled = true WHERE id = ?", cancelled);
        jdbc.update("UPDATE apt_trade SET missing_since = now() WHERE id = ?", missing);
        rows.trades(APT, DEAL_DATE.plusDays(3), 9, 80_000); // 같은 날 10건 → 일괄

        PageResponse<NotificationItem> page = service.findPage(me, 0, 20);
        assertThat(page.content()).extracting(NotificationItem::id).containsExactly(visible);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(service.countUnread(me)).isEqualTo(1);
    }

    @Test
    void 다른_회원의_알림은_보이지_않는다() {
        long event = event(rows.trade(APT, DEAL_DATE).insert(), 90_000);
        long mine = notify(me, event, T0);
        notify(other, event, T0);

        assertThat(service.findPage(me, 0, 20).content()).extracting(NotificationItem::id).containsExactly(mine);
        assertThat(service.countUnread(me)).isEqualTo(1);
    }

    @Test
    void 읽은_알림은_안_읽음_수에서_빠지고_목록에_읽음으로_보인다() {
        long read = notify(me, event(rows.trade(APT, DEAL_DATE).insert(), 90_000), T0);
        notify(me, event(rows.trade(APT, DEAL_DATE.plusDays(1)).insert(), 90_000), T0);

        service.markRead(me, read);

        assertThat(service.countUnread(me)).isEqualTo(1);
        assertThat(service.findPage(me, 0, 20).content())
                .filteredOn(item -> item.id() == read).singleElement()
                .extracting(NotificationItem::read).isEqualTo(true);
    }

    @Test
    void 이미_읽은_알림을_다시_읽어도_처음_읽은_시각을_둔다() {
        long id = notify(me, event(rows.trade(APT, DEAL_DATE).insert(), 90_000), T0);
        jdbc.update("UPDATE notification SET read_at = ? WHERE id = ?", T0.plusDays(1), id);

        service.markRead(me, id);

        assertThat(readAt(id)).isEqualTo(T0.plusDays(1));
    }

    @Test
    void 남의_알림과_없는_알림은_404이고_아무것도_바꾸지_않는다() {
        long others = notify(other, event(rows.trade(APT, DEAL_DATE).insert(), 90_000), T0);

        assertThatThrownBy(() -> service.markRead(me, others)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.markRead(me, others + 1000)).isInstanceOf(NotFoundException.class);
        assertThat(readAt(others)).isNull();
    }

    @Test
    void 모두_읽음은_내_안_읽은_알림만_바꾼다() {
        long event1 = event(rows.trade(APT, DEAL_DATE).insert(), 90_000);
        long event2 = event(rows.trade(APT, DEAL_DATE.plusDays(1)).insert(), 90_000);
        long alreadyRead = notify(me, event1, T0);
        jdbc.update("UPDATE notification SET read_at = ? WHERE id = ?", T0.plusDays(1), alreadyRead);
        long unread = notify(me, event2, T0);
        long others = notify(other, event1, T0);

        service.markAllRead(me);

        assertThat(readAt(unread)).isNotNull();
        assertThat(readAt(alreadyRead)).isEqualTo(T0.plusDays(1));
        assertThat(readAt(others)).isNull();
        assertThat(service.countUnread(me)).isZero();
    }

    @Test
    void 회원을_지우면_알림도_지워진다() {
        notify(me, event(rows.trade(APT, DEAL_DATE).insert(), 90_000), T0);

        jdbc.update("DELETE FROM member WHERE id = ?", me);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification", Long.class)).isZero();
    }

    private long event(long tradeId, int previousMaxAmount) {
        return jdbc.queryForObject("""
                INSERT INTO new_high_event (trade_id, previous_max_amount, detected_at)
                VALUES (?, ?, now()) RETURNING id
                """, Long.class, tradeId, previousMaxAmount);
    }

    private long notify(long memberId, long eventId, OffsetDateTime createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO notification (member_id, new_high_event_id, created_at)
                VALUES (?, ?, ?) RETURNING id
                """, Long.class, memberId, eventId, createdAt);
    }

    private OffsetDateTime readAt(long id) {
        return jdbc.queryForObject("SELECT read_at FROM notification WHERE id = ?", OffsetDateTime.class, id);
    }
}
