package com.dreamapt.collect;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 구×월 대조용 조회·일괄 쓰기 (ADR 003의 JdbcTemplate batch, ADR 004의 대조).
 * 거래는 대조 결과대로 id 기준으로 쓰므로 ON CONFLICT를 쓰지 않는다. 단지는 PK 기준 upsert를 유지한다.
 */
@Repository
@RequiredArgsConstructor
public class TradeReconcileRepository {

    // 주소(시군구·법정동·지번·도로명)가 바뀌면 옛 주소 기준 좌표를 지워서 다음 지오코딩 때 다시 받게 한다 (ADR 010).
    // SET의 오른쪽은 갱신 전 값을 읽으므로 아래 CASE의 apt_complex.* 는 바뀌기 전 주소다
    private static final String ADDRESS_CHANGED = """
            (apt_complex.sgg_cd, apt_complex.umd_cd, apt_complex.jibun, apt_complex.road_address)
                IS DISTINCT FROM (EXCLUDED.sgg_cd, EXCLUDED.umd_cd, EXCLUDED.jibun, EXCLUDED.road_address)""";

    private static final String INFO_CHANGED = """
            (apt_complex.name, apt_complex.sgg_cd, apt_complex.umd_cd, apt_complex.umd_nm,
             apt_complex.jibun, apt_complex.road_address, apt_complex.build_year)
                IS DISTINCT FROM
            (EXCLUDED.name, EXCLUDED.sgg_cd, EXCLUDED.umd_cd, EXCLUDED.umd_nm,
             EXCLUDED.jibun, EXCLUDED.road_address, EXCLUDED.build_year)""";

    // 단지 정보는 더 최근 거래일 때만 덮는다 (ADR 003). 같은 계약일이면 기존 값을 유지한다.
    // 값이 같아도 기준 날짜(source_deal_date)는 앞당긴다. 그러지 않으면 (A, 5월)에서 9월의 A를 건너뛴 뒤
    // 7월의 B가 덮는다. updated_at은 단지 정보가 실제로 바뀔 때만 바꾼다("값이 바뀐 시각").
    // 날짜 비교는 충돌한 행의 잠금을 잡은 뒤 평가되므로, 같은 단지를 다른 구×월 수집이 동시에 갱신해도 순서가 꼬이지 않는다
    private static final String UPSERT_COMPLEX = """
            INSERT INTO apt_complex (apt_seq, name, sgg_cd, umd_cd, umd_nm, jibun, road_address, build_year,
                                     source_deal_date)
            VALUES (:aptSeq, :name, :sggCd, :umdCd, :umdNm, :jibun, :roadAddress, :buildYear, :sourceDealDate)
            ON CONFLICT (apt_seq) DO UPDATE SET
                location = CASE WHEN %1$s THEN NULL ELSE apt_complex.location END,
                geocode_attempted_at = CASE WHEN %1$s THEN NULL ELSE apt_complex.geocode_attempted_at END,
                geocode_error = CASE WHEN %1$s THEN NULL ELSE apt_complex.geocode_error END,
                updated_at = CASE WHEN %2$s THEN now() ELSE apt_complex.updated_at END,
                name = EXCLUDED.name,
                sgg_cd = EXCLUDED.sgg_cd,
                umd_cd = EXCLUDED.umd_cd,
                umd_nm = EXCLUDED.umd_nm,
                jibun = EXCLUDED.jibun,
                road_address = EXCLUDED.road_address,
                build_year = EXCLUDED.build_year,
                source_deal_date = EXCLUDED.source_deal_date
            WHERE apt_complex.source_deal_date IS NULL
               OR EXCLUDED.source_deal_date > apt_complex.source_deal_date
            """.formatted(ADDRESS_CHANGED, INFO_CHANGED);

    // 트랜잭션이 끝나면 자동으로 풀린다. 같은 구×월의 동시 수집만 직렬화한다
    private static final String LOCK_MONTH = "SELECT pg_advisory_xact_lock(hashtext(:lockKey))";

    // missing 행도 포함한다 (다시 나타나면 짝지어 표시를 지운다)
    private static final String FIND_TRADES_IN_MONTH = """
            SELECT t.id, t.apt_seq, t.deal_date, t.deal_amount, t.exclu_use_ar, t.floor, t.apt_dong,
                   t.buyer_gbn, t.seller_gbn, t.dealing_gbn, t.agent_sgg_nm,
                   t.cancelled, t.cancel_date, t.registered_date, t.missing_since
            FROM apt_trade t
            JOIN apt_complex c ON c.apt_seq = t.apt_seq
            WHERE c.sgg_cd = :sggCd AND t.deal_date BETWEEN :from AND :to
            """;

    private static final String INSERT_TRADE = """
            INSERT INTO apt_trade (apt_seq, deal_date, deal_amount, exclu_use_ar, floor, apt_dong,
                                   buyer_gbn, seller_gbn, dealing_gbn, agent_sgg_nm,
                                   cancelled, cancel_date, registered_date)
            VALUES (:aptSeq, :dealDate, :dealAmount, :excluUseAr, :floor, :aptDong,
                    :buyerGbn, :sellerGbn, :dealingGbn, :agentSggNm,
                    :cancelled, :cancelDate, :registeredDate)
            """;

    // 대조에서 짝지어진 행만 온다. 불변 필드는 같으므로 나중에 채워지는 값과 missing 표시만 갱신한다
    private static final String UPDATE_TRADE = """
            UPDATE apt_trade SET
                apt_dong = :aptDong,
                cancelled = :cancelled,
                cancel_date = :cancelDate,
                registered_date = :registeredDate,
                missing_since = NULL,
                updated_at = now()
            WHERE id = :id
            """;

    private static final String MARK_MISSING = """
            UPDATE apt_trade SET missing_since = now(), updated_at = now()
            WHERE id IN (:ids) AND missing_since IS NULL
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public void lockMonth(String sggCd, String dealYm) {
        jdbc.query(LOCK_MONTH, new MapSqlParameterSource("lockKey", "apt_trade:" + sggCd + ":" + dealYm),
                (RowCallbackHandler) rs -> {
                });
    }

    /**
     * @param complexes aptSeq 기준으로 중복이 제거된 단지 목록
     */
    public void upsertComplexes(Collection<TradeRecord> complexes) {
        SqlParameterSource[] params = complexes.stream()
                .map(TradeReconcileRepository::complexParams)
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPSERT_COMPLEX, params);
    }

    public List<ExistingTrade> findTradesInMonth(String sggCd, LocalDate from, LocalDate to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sggCd", sggCd)
                .addValue("from", from)
                .addValue("to", to);
        return jdbc.query(FIND_TRADES_IN_MONTH, params, (rs, rowNum) -> existingTrade(rs));
    }

    public void insertTrades(List<TradeRecord> trades) {
        SqlParameterSource[] params = trades.stream()
                .map(TradeReconcileRepository::tradeParams)
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(INSERT_TRADE, params);
    }

    public void updateTrades(List<ReconcilePlan.Update> updates) {
        SqlParameterSource[] params = updates.stream()
                .map(u -> tradeParams(u.record()).addValue("id", u.id()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(UPDATE_TRADE, params);
    }

    public void markMissing(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return; // IN ()은 문법 오류
        }
        jdbc.update(MARK_MISSING, new MapSqlParameterSource("ids", ids));
    }

    private static ExistingTrade existingTrade(ResultSet rs) throws SQLException {
        return new ExistingTrade(
                rs.getLong("id"),
                rs.getString("apt_seq"),
                rs.getObject("deal_date", LocalDate.class),
                rs.getInt("deal_amount"),
                rs.getBigDecimal("exclu_use_ar"),
                rs.getShort("floor"),
                rs.getString("apt_dong"),
                rs.getString("buyer_gbn"),
                rs.getString("seller_gbn"),
                rs.getString("dealing_gbn"),
                rs.getString("agent_sgg_nm"),
                rs.getBoolean("cancelled"),
                rs.getObject("cancel_date", LocalDate.class),
                rs.getObject("registered_date", LocalDate.class),
                rs.getObject("missing_since", OffsetDateTime.class));
    }

    private static SqlParameterSource complexParams(TradeRecord r) {
        return new MapSqlParameterSource()
                .addValue("aptSeq", r.aptSeq())
                .addValue("name", r.aptNm())
                .addValue("sggCd", r.sggCd())
                .addValue("umdCd", r.umdCd())
                .addValue("umdNm", r.umdNm(), Types.VARCHAR)
                .addValue("jibun", r.jibun(), Types.VARCHAR)
                .addValue("roadAddress", r.roadAddress(), Types.VARCHAR)
                .addValue("buildYear", r.buildYear(), Types.SMALLINT)
                .addValue("sourceDealDate", r.dealDate());
    }

    private static MapSqlParameterSource tradeParams(TradeRecord r) {
        return new MapSqlParameterSource()
                .addValue("aptSeq", r.aptSeq())
                .addValue("dealDate", r.dealDate())
                .addValue("dealAmount", r.dealAmount())
                .addValue("excluUseAr", r.excluUseAr())
                .addValue("floor", r.floor(), Types.SMALLINT)
                .addValue("aptDong", r.aptDong(), Types.VARCHAR)
                .addValue("buyerGbn", r.buyerGbn(), Types.VARCHAR)
                .addValue("sellerGbn", r.sellerGbn(), Types.VARCHAR)
                .addValue("dealingGbn", r.dealingGbn(), Types.VARCHAR)
                .addValue("agentSggNm", r.agentSggNm(), Types.VARCHAR)
                .addValue("cancelled", r.cancelled())
                .addValue("cancelDate", r.cancelDate(), Types.DATE)
                .addValue("registeredDate", r.registeredDate(), Types.DATE);
    }
}
