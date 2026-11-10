package com.dreamapt.watchlist;

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

    private final NamedParameterJdbcTemplate jdbc;

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