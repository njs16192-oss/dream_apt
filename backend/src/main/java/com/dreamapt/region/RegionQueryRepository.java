package com.dreamapt.region;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 구 단위 거래 집계. valid_apt_trade의 윈도 파티션에 sgg_cd가 있어서
 * sgg_cd·deal_date 조건이 윈도 계산 전에 적용된다 (ADR 007 ③).
 */
@Repository
@RequiredArgsConstructor
public class RegionQueryRepository {

    private static final String MONTHLY_VOLUMES = """
            SELECT date_trunc('month', deal_date::timestamp)::date AS month,
                   count(*) FILTER (WHERE NOT is_bulk) AS cnt,
                   count(*) FILTER (WHERE is_bulk) AS bulk_count
            FROM valid_apt_trade
            WHERE sgg_cd = :sggCd AND deal_date >= :from AND deal_date < :toExclusive
            GROUP BY 1
            ORDER BY 1
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** from 이상 toExclusive 미만 계약일의 월별 거래량. 기간 제한은 서비스에서 한다. */
    public List<MonthlyVolumeRow> findMonthlyVolumes(String sggCd, LocalDate from, LocalDate toExclusive) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sggCd", sggCd)
                .addValue("from", from)
                .addValue("toExclusive", toExclusive);
        return jdbc.query(MONTHLY_VOLUMES, params, (rs, i) -> new MonthlyVolumeRow(
                YearMonth.from(rs.getObject("month", LocalDate.class)),
                rs.getInt("cnt"),
                rs.getInt("bulk_count")));
    }

    /** 월별 거래량. provisional은 서비스에서 기준일로 정한다. */
    public record MonthlyVolumeRow(YearMonth month, int count, int bulkCount) {
    }
}
