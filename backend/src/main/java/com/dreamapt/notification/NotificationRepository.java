package com.dreamapt.notification;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** 회원별 신고가 알림 (ADR 011 ⑥). 알림 생성은 신고가 기록과 한 문장이라 NewHighEventRepository에 있다 */
@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    // 보이는 알림: 신고가 거래가 지금도 유효하고 일괄이 아닌 것 (ADR 011 ⑤ "기록은 스냅샷이다"). 목록·전체 수·안 읽음 수가 모두 이 조건을 쓴다.
    // valid_apt_trade를 id로만 조인하면 조건이 뷰 안 is_bulk 윈도(PARTITION BY sgg_cd, apt_seq, deal_date)까지 내려가지 못해
    // 전체 거래에 윈도를 계산한다. 그래서 apt_trade에서 apt_seq·deal_date를 얻어 LATERAL 안에서 거른다 (그 단지·그날 거래만 읽는다).
    // LIMIT 1은 결과를 바꾸지 않는다(v.id = t.id라 많아야 한 행). 집계도 LIMIT도 없는 단순 서브쿼리는 플래너가 바깥 조인으로 끌어올려서
    // 조건이 다시 윈도 밖에 남는다 (로컬 DB 알림 50건 목록: LIMIT 없이 888ms, 있으면 12ms). WatchlistRepository.FIND_ALL은 max()라 괜찮다
    private static final String VISIBLE_FROM = """
            FROM notification n
            JOIN new_high_event e ON e.id = n.new_high_event_id
            JOIN apt_trade t ON t.id = e.trade_id
            JOIN apt_complex c ON c.apt_seq = t.apt_seq
            CROSS JOIN LATERAL (
                SELECT v.area_group, v.exclu_use_ar, v.floor, v.deal_date, v.deal_amount
                FROM valid_apt_trade v
                WHERE v.apt_seq = t.apt_seq
                  AND v.deal_date = t.deal_date
                  AND v.id = t.id
                  AND NOT v.is_bulk
                LIMIT 1
            ) v
            WHERE n.member_id = :memberId
            """;

    private static final String FIND_PAGE = """
            SELECT n.id, t.apt_seq, c.name, v.area_group, v.exclu_use_ar, v.floor, v.deal_date, v.deal_amount,
                   e.previous_max_amount, n.created_at, n.read_at
            """ + VISIBLE_FROM + """
            ORDER BY n.created_at DESC, n.id DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT = "SELECT count(*)\n" + VISIBLE_FROM;

    private static final String COUNT_UNREAD = COUNT + "  AND n.read_at IS NULL\n";

    // 이미 읽은 알림은 처음 읽은 시각을 둔다. 남의 알림·없는 알림은 0행
    private static final String MARK_READ = """
            UPDATE notification SET read_at = COALESCE(read_at, now())
            WHERE id = :id AND member_id = :memberId
            """;

    private static final String MARK_ALL_READ = """
            UPDATE notification SET read_at = now()
            WHERE member_id = :memberId AND read_at IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** 최신순 (만든 시각 내림차순, 같으면 id 내림차순) */
    public List<NotificationItem> findPage(long memberId, int limit, long offset) {
        MapSqlParameterSource params = memberParam(memberId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(FIND_PAGE, params, (rs, i) -> new NotificationItem(
                rs.getLong("id"),
                rs.getString("apt_seq"),
                rs.getString("name"),
                rs.getInt("area_group"),
                rs.getBigDecimal("exclu_use_ar"),
                rs.getInt("floor"),
                rs.getObject("deal_date", LocalDate.class),
                rs.getInt("deal_amount"),
                rs.getInt("previous_max_amount"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("read_at", OffsetDateTime.class) != null));
    }

    public long count(long memberId) {
        return jdbc.queryForObject(COUNT, memberParam(memberId), Long.class);
    }

    public long countUnread(long memberId) {
        return jdbc.queryForObject(COUNT_UNREAD, memberParam(memberId), Long.class);
    }

    /** 내 알림이면 읽음으로 표시하고 true. 남의 알림이거나 없으면 false */
    public boolean markRead(long memberId, long id) {
        return jdbc.update(MARK_READ, memberParam(memberId).addValue("id", id)) == 1;
    }

    public int markAllRead(long memberId) {
        return jdbc.update(MARK_ALL_READ, memberParam(memberId));
    }

    private static MapSqlParameterSource memberParam(long memberId) {
        return new MapSqlParameterSource("memberId", memberId);
    }
}
