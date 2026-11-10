package com.dreamapt.watchlist;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** 관심단지. PK가 (member_id, apt_seq) 두 컬럼이라 JPA 대신 SQL로 다룬다 */
@Repository
@RequiredArgsConstructor
public class WatchlistRepository {

    // 이미 있으면 아무것도 안 한다 → PUT을 여러 번 보내도 결과가 같다
    private static final String ADD = """
            INSERT INTO watchlist (member_id, apt_seq, created_at)
            VALUES (:memberId, :aptSeq, now())
            ON CONFLICT (member_id, apt_seq) DO NOTHING
            """;

    private static final String REMOVE = """
            DELETE FROM watchlist
            WHERE member_id = :memberId AND apt_seq = :aptSeq
            """;

    private static final String COUNT = """
            SELECT COUNT(*) FROM watchlist
            WHERE member_id = :memberId
            """;

    private static final String EXISTS = """
            SELECT EXISTS (
                SELECT 1 FROM watchlist
                WHERE member_id = :memberId AND apt_seq = :aptSeq
            )
            """;

    // 최근 추가순. 최근 거래일은 LATERAL 안에서 apt_seq로 걸러 뷰의 윈도 계산이 그 단지에만 돌게 한다 (ComplexQueryRepository.SEARCH와 같은 방식).
    // GROUP BY 없는 max()는 거래가 0건이어도 한 행(null)을 돌려주므로, 거래 없는 단지도 CROSS JOIN에서 빠지지 않는다
    private static final String FIND_ALL = """
            SELECT w.apt_seq, c.name, c.sgg_cd, r.sgg_nm, recent.last_deal_date, w.created_at
            FROM watchlist w
            JOIN apt_complex c ON c.apt_seq = w.apt_seq
            LEFT JOIN region r ON r.sgg_cd = c.sgg_cd
            CROSS JOIN LATERAL (
                SELECT max(v.deal_date) AS last_deal_date FROM valid_apt_trade v
                WHERE v.apt_seq = w.apt_seq AND NOT v.is_bulk
            ) recent
            WHERE w.member_id = :memberId
            ORDER BY w.created_at DESC, w.apt_seq
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public List<WatchlistItem> findAll(long memberId) {
        return jdbc.query(FIND_ALL, new MapSqlParameterSource("memberId", memberId), (rs, i) -> new WatchlistItem(
                rs.getString("apt_seq"),
                rs.getString("name"),
                rs.getString("sgg_cd"),
                rs.getString("sgg_nm"),
                rs.getObject("last_deal_date", LocalDate.class),
                rs.getObject("created_at", OffsetDateTime.class)));
    }

    public void add(long memberId, String aptSeq) {
        jdbc.update(ADD, params(memberId, aptSeq));
    }

    public void remove(long memberId, String aptSeq) {
        jdbc.update(REMOVE, params(memberId, aptSeq));
    }

    public int count(long memberId) {
        return jdbc.queryForObject(COUNT,
                new MapSqlParameterSource("memberId", memberId), Integer.class);
    }

    public boolean exists(long memberId, String aptSeq) {
        return jdbc.queryForObject(EXISTS, params(memberId, aptSeq), Boolean.class);
    }

    private static MapSqlParameterSource params(long memberId, String aptSeq) {
        return new MapSqlParameterSource()
                .addValue("memberId", memberId)
                .addValue("aptSeq", aptSeq);
    }
}