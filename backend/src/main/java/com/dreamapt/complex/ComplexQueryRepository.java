package com.dreamapt.complex;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 단지 조회. 거래는 모두 valid_apt_trade 뷰로 읽는다 (ADR 007 ③).
 * 일괄 거래는 요약값·정렬·통계에서 제외하고, 거래 목록에는 포함한다 (ADR 007 ②).
 */
@Repository
@RequiredArgsConstructor
public class ComplexQueryRepository {

    // 이름 부분 일치(ILIKE) 또는 trigram 유사도(%)로 찾고, 유사도 → 최근 거래 수 순으로 정렬한다.
    // 최근 거래 수는 LATERAL 안에서 apt_seq 조건으로 세서 뷰의 윈도가 그 단지에만 계산되게 한다
    private static final String SEARCH = """
            SELECT c.apt_seq, c.name, c.sgg_cd, r.sgg_nm, c.umd_nm, c.build_year, recent.cnt AS recent_trade_count
            FROM apt_complex c
            LEFT JOIN region r ON r.sgg_cd = c.sgg_cd
            CROSS JOIN LATERAL (
                SELECT count(*) AS cnt FROM valid_apt_trade v
                WHERE v.apt_seq = c.apt_seq AND v.deal_date > :recentAfter AND NOT v.is_bulk
            ) recent
            WHERE (c.name ILIKE :pattern ESCAPE '\\' OR c.name % :q)
            """;

    private static final String SEARCH_ORDER = """
            ORDER BY similarity(c.name, :q) DESC, recent.cnt DESC, c.apt_seq
            LIMIT :limit
            """;

    private static final String FIND_COMPLEX = """
            SELECT c.apt_seq, c.name, c.sgg_cd, r.sgg_nm, c.umd_nm, c.build_year, c.road_address,
                   ST_Y(c.location) AS lat, ST_X(c.location) AS lng
            FROM apt_complex c
            LEFT JOIN region r ON r.sgg_cd = c.sgg_cd
            WHERE c.apt_seq = :aptSeq
            """;

    // 반경 검색 (ADR 010 ⑧). 안쪽에서 반경으로 거르고 거리순으로 LIMIT까지 한 뒤 바깥에서 최근 거래 수를 붙인다.
    // 그래야 거래 수 집계(뷰의 윈도 계산)가 반경 안 전체가 아니라 최대 limit개 단지에만 돈다.
    // totalCount는 LIMIT 전에 count(*) OVER ()로 센다. location::geography는 인덱스 식과 같아야 GIST 인덱스를 쓴다
    private static final String NEARBY = """
            WITH center AS (
                SELECT ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography AS g
            ),
            nearest AS (
                SELECT c.apt_seq, c.name, c.sgg_cd, c.umd_nm, c.build_year,
                       ST_Y(c.location) AS lat, ST_X(c.location) AS lng,
                       ST_Distance(c.location::geography, center.g) AS distance,
                       count(*) OVER () AS total_count
                FROM apt_complex c, center
                WHERE ST_DWithin(c.location::geography, center.g, :radius)
                ORDER BY distance, c.apt_seq
                LIMIT :limit
            )
            SELECT n.apt_seq, n.name, r.sgg_nm, n.umd_nm, n.build_year, n.lat, n.lng,
                   ROUND(n.distance)::int AS distance_m, n.total_count, recent.cnt AS recent_trade_count
            FROM nearest n
            LEFT JOIN region r ON r.sgg_cd = n.sgg_cd
            CROSS JOIN LATERAL (
                SELECT count(*) AS cnt FROM valid_apt_trade v
                WHERE v.apt_seq = n.apt_seq AND v.deal_date > :recentAfter AND NOT v.is_bulk
            ) recent
            ORDER BY n.distance, n.apt_seq
            """;

    // 마지막 거래 금액: 일괄 거래를 뺀 거래 중 계약일이 가장 늦은 것, 같은 날이면 id가 큰(늦게 수집된) 것
    private static final String AREA_GROUPS = """
            SELECT area_group,
                   array_agg(DISTINCT exclu_use_ar ORDER BY exclu_use_ar) AS exclusive_areas,
                   count(*) FILTER (WHERE NOT is_bulk) AS trade_count,
                   count(*) FILTER (WHERE is_bulk) AS bulk_count,
                   max(deal_date) FILTER (WHERE NOT is_bulk) AS last_deal_date,
                   (array_agg(deal_amount ORDER BY deal_date DESC, id DESC) FILTER (WHERE NOT is_bulk))[1]
                       AS last_deal_amount
            FROM valid_apt_trade
            WHERE apt_seq = :aptSeq
            GROUP BY area_group
            ORDER BY area_group
            """;

    private static final String AREA_GROUP_EXISTS = """
            SELECT EXISTS (SELECT 1 FROM valid_apt_trade WHERE apt_seq = :aptSeq AND area_group = :areaGroup)
            """;

    // percentile_cont는 double precision을 돌려준다. ROUND(double)은 짝수 쪽 반올림이라
    // numeric으로 바꾼 뒤 반올림한다 (HALF_UP). 반올림은 여기서만 하고 Java에서는 다시 하지 않는다 (ADR 007 ④)
    private static final String MONTHLY_PRICES = """
            SELECT date_trunc('month', deal_date::timestamp)::date AS month,
                   ROUND((percentile_cont(0.5) WITHIN GROUP (ORDER BY deal_amount))::numeric)::int AS median_amount,
                   count(*) AS cnt
            FROM valid_apt_trade
            WHERE apt_seq = :aptSeq AND area_group = :areaGroup AND NOT is_bulk
              AND deal_date < :toExclusive
            """;

    private static final String TRADES = """
            SELECT id, deal_date, deal_amount, exclu_use_ar, area_group, floor, apt_dong,
                   dealing_gbn, buyer_gbn, seller_gbn, registered_date, is_bulk
            FROM valid_apt_trade
            WHERE apt_seq = :aptSeq
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public List<ComplexSearchItem> search(String q, String sggCd, LocalDate recentAfter, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", q)
                .addValue("pattern", "%" + escapeLike(q) + "%")
                .addValue("recentAfter", recentAfter)
                .addValue("limit", limit);
        String sql = SEARCH;
        if (sggCd != null) {
            sql += "  AND c.sgg_cd = :sggCd\n";
            params.addValue("sggCd", sggCd);
        }
        return jdbc.query(sql + SEARCH_ORDER, params, (rs, i) -> new ComplexSearchItem(
                rs.getString("apt_seq"),
                rs.getString("name"),
                rs.getString("sgg_cd"),
                rs.getString("sgg_nm"),
                rs.getString("umd_nm"),
                getInteger(rs, "build_year"),
                rs.getInt("recent_trade_count")));
    }

    public Optional<ComplexRow> findComplex(String aptSeq) {
        return jdbc.query(FIND_COMPLEX, new MapSqlParameterSource("aptSeq", aptSeq), (rs, i) -> new ComplexRow(
                rs.getString("apt_seq"),
                rs.getString("name"),
                rs.getString("sgg_cd"),
                rs.getString("sgg_nm"),
                rs.getString("umd_nm"),
                getInteger(rs, "build_year"),
                rs.getString("road_address"),
                getDouble(rs, "lat"),
                getDouble(rs, "lng"))).stream().findFirst();
    }

    /** 반경 안 단지를 가까운 순으로 limit개, 그리고 반경 안 전체 수 */
    public NearbyRows findNearby(double lat, double lng, int radius, int limit, LocalDate recentAfter) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("lat", lat)
                .addValue("lng", lng)
                .addValue("radius", radius)
                .addValue("limit", limit)
                .addValue("recentAfter", recentAfter);
        int[] total = { 0 };
        List<NearbyComplex> items = jdbc.query(NEARBY, params, (rs, i) -> {
            total[0] = rs.getInt("total_count");
            return new NearbyComplex(
                    rs.getString("apt_seq"),
                    rs.getString("name"),
                    rs.getString("sgg_nm"),
                    rs.getString("umd_nm"),
                    getInteger(rs, "build_year"),
                    rs.getDouble("lat"),
                    rs.getDouble("lng"),
                    rs.getInt("distance_m"),
                    rs.getInt("recent_trade_count"));
        });
        return new NearbyRows(items, total[0]);
    }

    public List<AreaGroupSummary> findAreaGroups(String aptSeq) {
        return jdbc.query(AREA_GROUPS, new MapSqlParameterSource("aptSeq", aptSeq), (rs, i) -> new AreaGroupSummary(
                rs.getInt("area_group"),
                toAreas(rs.getArray("exclusive_areas")),
                rs.getInt("trade_count"),
                rs.getInt("bulk_count"),
                rs.getObject("last_deal_date", LocalDate.class),
                getInteger(rs, "last_deal_amount")));
    }

    public boolean existsAreaGroup(String aptSeq, int areaGroup) {
        return Boolean.TRUE.equals(jdbc.queryForObject(AREA_GROUP_EXISTS, new MapSqlParameterSource()
                .addValue("aptSeq", aptSeq)
                .addValue("areaGroup", areaGroup), Boolean.class));
    }

    /** from 이상(null이면 처음부터) toExclusive 미만 계약일의 월별 중앙값·건수, 일괄 거래 제외. */
    public List<MonthlyPriceRow> findMonthlyPrices(String aptSeq, int areaGroup, LocalDate from,
            LocalDate toExclusive) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("aptSeq", aptSeq)
                .addValue("areaGroup", areaGroup)
                .addValue("from", from)
                .addValue("toExclusive", toExclusive);
        String sql = MONTHLY_PRICES + (from == null ? "" : "  AND deal_date >= :from\n") + "GROUP BY 1\nORDER BY 1\n";
        return jdbc.query(sql, params, (rs, i) -> new MonthlyPriceRow(
                YearMonth.from(rs.getObject("month", LocalDate.class)),
                rs.getInt("median_amount"),
                rs.getInt("cnt")));
    }

    public long countTrades(String aptSeq, Integer areaGroup) {
        MapSqlParameterSource params = tradeParams(aptSeq, areaGroup);
        String sql = "SELECT count(*) FROM (" + TRADES + areaGroupCondition(areaGroup) + ") t";
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count == null ? 0 : count;
    }

    /** 최신순 (계약일 내림차순, 같은 날이면 늦게 수집된 순). 일괄 거래도 포함한다. */
    public List<TradeItem> findTrades(String aptSeq, Integer areaGroup, int limit, long offset) {
        MapSqlParameterSource params = tradeParams(aptSeq, areaGroup)
                .addValue("limit", limit)
                .addValue("offset", offset);
        String sql = TRADES + areaGroupCondition(areaGroup)
                + "ORDER BY deal_date DESC, id DESC\nLIMIT :limit OFFSET :offset\n";
        return jdbc.query(sql, params, (rs, i) -> new TradeItem(
                rs.getLong("id"),
                rs.getObject("deal_date", LocalDate.class),
                rs.getInt("deal_amount"),
                rs.getBigDecimal("exclu_use_ar"),
                rs.getInt("area_group"),
                rs.getInt("floor"),
                rs.getString("apt_dong"),
                rs.getString("dealing_gbn"),
                rs.getString("buyer_gbn"),
                rs.getString("seller_gbn"),
                rs.getObject("registered_date", LocalDate.class),
                rs.getBoolean("is_bulk")));
    }

    private static MapSqlParameterSource tradeParams(String aptSeq, Integer areaGroup) {
        return new MapSqlParameterSource()
                .addValue("aptSeq", aptSeq)
                .addValue("areaGroup", areaGroup);
    }

    private static String areaGroupCondition(Integer areaGroup) {
        return areaGroup == null ? "" : "  AND area_group = :areaGroup\n";
    }

    /** LIKE 패턴의 와일드카드(%, _)와 이스케이프 문자를 글자 그대로 찾도록 바꾼다. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double getDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static List<BigDecimal> toAreas(Array array) throws SQLException {
        return Arrays.asList((BigDecimal[]) array.getArray());
    }

    /** 단지 기본 정보 (거래 요약 제외). 좌표가 없으면 lat·lng는 null */
    public record ComplexRow(String aptSeq, String name, String sggCd, String sggName, String umdName,
            Integer buildYear, String roadAddress, Double lat, Double lng) {
    }

    /** 반경 검색 결과. totalCount는 limit으로 자르기 전 반경 안 전체 수 */
    public record NearbyRows(List<NearbyComplex> items, int totalCount) {
    }

    /** 월별 중앙값·건수. provisional은 서비스에서 기준일로 정한다. */
    public record MonthlyPriceRow(YearMonth month, int medianAmount, int count) {
    }
}
