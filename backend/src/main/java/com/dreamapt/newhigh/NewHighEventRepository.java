package com.dreamapt.newhigh;

import java.time.LocalDate;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** 신고가 기록 (ADR 011 ⑤). 판단과 기록을 INSERT … SELECT 한 문장으로 한다 */
@Repository
@RequiredArgsConstructor
public class NewHighEventRepository {

    // 거래 T(계약일 >= from, 비일괄)마다, 같은 그룹(단지, 면적 그룹)에서 계약일이 더 이른 비일괄 거래의 최고가를 LATERAL로 구한다.
    // - 같은 날 거래는 서로의 기준이 아니다 (p.deal_date < t.deal_date)
    // - 이전 거래가 없으면 max()가 null이라 > 비교가 거짓 → 신고가 아님. 같은 금액도 아님
    // - p.apt_seq = t.apt_seq가 뷰 안 is_bulk 윈도(PARTITION에 apt_seq)까지 내려가서 그 단지 이력만 읽는다.
    //   윈도 함수 방식과의 비교는 ADR 011 ⑤ "SQL 방식"
    // - ON CONFLICT DO NOTHING: 이미 기록한 거래는 그대로 둔다 (스냅샷, detected_at 유지)
    // PR 7: RETURNING은 실제로 들어간 행만 돌려준다. 이 문장을 WITH inserted AS (… RETURNING id, trade_id)로 감싸
    // 이번 실행에서 새로 기록된 신고가에만 알림을 만든다. 그래서 판단과 기록을 한 문장으로 둔다
    private static final String RECORD_SINCE = """
            INSERT INTO new_high_event (trade_id, previous_max_amount, detected_at)
            SELECT t.id, prev.max_amount, now()
            FROM valid_apt_trade t
            CROSS JOIN LATERAL (
                SELECT max(p.deal_amount) AS max_amount
                FROM valid_apt_trade p
                WHERE p.apt_seq = t.apt_seq
                  AND p.area_group = t.area_group
                  AND p.deal_date < t.deal_date
                  AND NOT p.is_bulk
            ) prev
            WHERE t.deal_date >= :from
              AND NOT t.is_bulk
              AND t.deal_amount > prev.max_amount
            ON CONFLICT (trade_id) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** 계약일이 from 이후(포함)인 거래를 판단해 새 신고가를 기록한다. 새로 넣은 행 수를 돌려준다 */
    public int recordSince(LocalDate from) {
        return jdbc.update(RECORD_SINCE, new MapSqlParameterSource("from", from));
    }
}
