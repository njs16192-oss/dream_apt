package com.dreamapt.collect;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 단지·거래 일괄 upsert (PostgreSQL ON CONFLICT).
 *
 * <p>DO UPDATE에 {@code WHERE ... IS DISTINCT FROM} 조건을 걸어 값이 바뀐 행만 갱신한다.
 * 그래서 batch 결과의 영향 행 수는 "신규 + 실제 갱신" 건수가 된다.
 * 주의: JDBC URL에 {@code reWriteBatchedInserts=true}를 켜면 영향 행 수가 -2(SUCCESS_NO_INFO)로 와서 건수 계산이 깨진다.
 */
@Repository
@RequiredArgsConstructor
public class TradeUpsertRepository {

    private static final String UPSERT_COMPLEX = """
            INSERT INTO apt_complex (apt_seq, name, sgg_cd, umd_cd, umd_nm, jibun, road_address, build_year)
            VALUES (:aptSeq, :name, :sggCd, :umdCd, :umdNm, :jibun, :roadAddress, :buildYear)
            ON CONFLICT (apt_seq) DO UPDATE SET
                name = EXCLUDED.name,
                sgg_cd = EXCLUDED.sgg_cd,
                umd_cd = EXCLUDED.umd_cd,
                umd_nm = EXCLUDED.umd_nm,
                jibun = EXCLUDED.jibun,
                road_address = EXCLUDED.road_address,
                build_year = EXCLUDED.build_year,
                updated_at = now()
            WHERE (apt_complex.name, apt_complex.sgg_cd, apt_complex.umd_cd, apt_complex.umd_nm,
                   apt_complex.jibun, apt_complex.road_address, apt_complex.build_year)
                IS DISTINCT FROM
                  (EXCLUDED.name, EXCLUDED.sgg_cd, EXCLUDED.umd_cd, EXCLUDED.umd_nm,
                   EXCLUDED.jibun, EXCLUDED.road_address, EXCLUDED.build_year)
            """;

    // 키(uk_apt_trade)에 없는, 나중에 채워지는 값만 갱신한다 (ADR 002)
    private static final String UPSERT_TRADE = """
            INSERT INTO apt_trade (apt_seq, deal_date, deal_amount, exclu_use_ar, floor, apt_dong,
                                   buyer_gbn, seller_gbn, dealing_gbn, agent_sgg_nm,
                                   cancelled, cancel_date, registered_date)
            VALUES (:aptSeq, :dealDate, :dealAmount, :excluUseAr, :floor, :aptDong,
                    :buyerGbn, :sellerGbn, :dealingGbn, :agentSggNm,
                    :cancelled, :cancelDate, :registeredDate)
            ON CONFLICT ON CONSTRAINT uk_apt_trade DO UPDATE SET
                apt_dong = EXCLUDED.apt_dong,
                cancelled = EXCLUDED.cancelled,
                cancel_date = EXCLUDED.cancel_date,
                registered_date = EXCLUDED.registered_date,
                updated_at = now()
            WHERE (apt_trade.apt_dong, apt_trade.cancelled, apt_trade.cancel_date, apt_trade.registered_date)
                IS DISTINCT FROM
                  (EXCLUDED.apt_dong, EXCLUDED.cancelled, EXCLUDED.cancel_date, EXCLUDED.registered_date)
            """;

    private static final String COUNT_TRADES = """
            SELECT count(*) FROM apt_trade
            WHERE apt_seq IN (:aptSeqs) AND deal_date BETWEEN :from AND :to
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * @param complexes aptSeq 기준으로 중복이 제거된 단지 목록
     * @return 신규 + 실제로 값이 바뀐 행 수
     */
    public int upsertComplexes(Collection<TradeRecord> complexes) {
        SqlParameterSource[] params = complexes.stream()
                .map(TradeUpsertRepository::complexParams)
                .toArray(SqlParameterSource[]::new);
        return sum(jdbc.batchUpdate(UPSERT_COMPLEX, params));
    }

    /**
     * @return 신규 + 실제로 값이 바뀐 행 수
     */
    public int upsertTrades(List<TradeRecord> trades) {
        SqlParameterSource[] params = trades.stream()
                .map(TradeUpsertRepository::tradeParams)
                .toArray(SqlParameterSource[]::new);
        return sum(jdbc.batchUpdate(UPSERT_TRADE, params));
    }

    /**
     * 주어진 단지들의 계약일 범위 내 거래 수. upsert 전후 비교로 신규 건수를 구하는 데 쓴다.
     */
    public long countTrades(Collection<String> aptSeqs, LocalDate from, LocalDate to) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("aptSeqs", aptSeqs)
                .addValue("from", from)
                .addValue("to", to);
        Long count = jdbc.queryForObject(COUNT_TRADES, params, Long.class);
        return count == null ? 0 : count;
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
                .addValue("buildYear", r.buildYear(), Types.SMALLINT);
    }

    private static SqlParameterSource tradeParams(TradeRecord r) {
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

    private static int sum(int[] counts) {
        return Arrays.stream(counts).sum();
    }
}
