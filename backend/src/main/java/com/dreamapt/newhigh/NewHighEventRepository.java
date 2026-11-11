package com.dreamapt.newhigh;

import java.time.LocalDate;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/** 신고가 기록과 알림 생성 (ADR 011 ⑤ ⑥). 판단·기록·알림을 한 문장으로 한다 */
@Repository
@RequiredArgsConstructor
public class NewHighEventRepository {

    // inserted: 거래 T(계약일 >= from, 비일괄)마다, 같은 그룹(단지, 면적 그룹)에서 계약일이 더 이른 비일괄 거래의 최고가를 LATERAL로 구한다.
    // - 같은 날 거래는 서로의 기준이 아니다 (p.deal_date < t.deal_date)
    // - 이전 거래가 없으면 max()가 null이라 > 비교가 거짓 → 신고가 아님. 같은 금액도 아님
    // - p.apt_seq = t.apt_seq가 뷰 안 is_bulk 윈도(PARTITION에 apt_seq)까지 내려가서 그 단지 이력만 읽는다.
    //   윈도 함수 방식과의 비교는 ADR 011 ⑤ "SQL 방식"
    // - ON CONFLICT DO NOTHING: 이미 기록한 거래는 그대로 둔다 (스냅샷, detected_at 유지)
    // notified: RETURNING은 실제로 들어간 행만 돌려준다. 그래서 이번 실행에서 새로 기록된 신고가에만 알림을 만든다
    // - WITH 안의 문장들은 같은 스냅샷을 본다. new_high_event 테이블을 다시 읽으면 방금 넣은 행이 안 보이므로 inserted의 출력을 쓴다
    // - 알림 FK(new_high_event_id) 검사는 문장 끝에 하므로, 같은 문장에서 넣은 기록을 가리켜도 통과한다
    // - ON CONFLICT가 필요 없다: inserted는 모두 새 기록이고, watchlist PK (member_id, apt_seq)라 한 기록에 같은 회원이 두 번 오지 않는다
    private static final String RECORD_SINCE = """
            WITH inserted AS (
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
                RETURNING id, trade_id
            ), notified AS (
                INSERT INTO notification (member_id, new_high_event_id, created_at)
                SELECT w.member_id, i.id, now()
                FROM inserted i
                JOIN apt_trade t ON t.id = i.trade_id
                JOIN watchlist w ON w.apt_seq = t.apt_seq
                RETURNING 1
            )
            SELECT (SELECT count(*) FROM inserted) AS recorded,
                   (SELECT count(*) FROM notified) AS notified
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 계약일이 from 이후(포함)인 거래를 판단해 새 신고가를 기록하고, 그 단지를 관심단지로 둔 회원에게 알림을 만든다.
     * 새로 넣은 기록 수와 알림 수를 돌려준다
     */
    public RecordResult recordSince(LocalDate from) {
        return jdbc.queryForObject(RECORD_SINCE, new MapSqlParameterSource("from", from),
                (rs, i) -> new RecordResult(rs.getInt("recorded"), rs.getInt("notified")));
    }

    /**
     * @param recorded 새로 기록한 신고가 수
     * @param notified 새로 만든 알림 수
     */
    public record RecordResult(int recorded, int notified) {
    }
}
