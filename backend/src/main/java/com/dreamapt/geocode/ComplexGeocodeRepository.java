package com.dreamapt.geocode;

import java.sql.Types;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 단지 좌표 읽기·쓰기 (ADR 010). location은 엔티티에 매핑하지 않고 여기서만 쓴다.
 * 쓰기는 한 단지씩 바로 커밋한다. API 호출 중에 트랜잭션·커넥션을 잡지 않기 위해서다.
 */
@Repository
@RequiredArgsConstructor
public class ComplexGeocodeRepository {

    // 좌표가 없고, 아직 시도하지 않았거나(retryFailed면) 실패로 기록된 단지.
    // 다시 시도한 실패 단지는 attempted_at이 늦어져 뒤로 가므로, 나눠 실행해도 같은 단지만 반복하지 않는다
    private static final String TARGET_CONDITION = """
            c.location IS NULL
              AND (c.geocode_attempted_at IS NULL OR (:retryFailed AND c.geocode_error IS NOT NULL))
            """;

    private static final String FIND_TARGETS = """
            SELECT c.apt_seq, c.sgg_cd, c.umd_cd, r.sido_nm, r.sgg_nm, c.umd_nm, c.jibun, c.road_address
            FROM apt_complex c
            LEFT JOIN region r ON r.sgg_cd = c.sgg_cd
            WHERE %s
            ORDER BY c.geocode_attempted_at NULLS FIRST, c.apt_seq
            LIMIT :limit
            """.formatted(TARGET_CONDITION);

    private static final String COUNT_TARGETS = "SELECT count(*) FROM apt_complex c WHERE " + TARGET_CONDITION;

    // 단지를 고른 뒤 수집이 주소를 바꿨으면 옛 주소의 좌표이므로 저장하지 않는다.
    // 주소가 바뀌면 upsert가 지오코딩 기록을 지워 두므로 다음 실행에서 새 주소로 다시 받는다
    private static final String SAME_ADDRESS = """
            apt_seq = :aptSeq AND sgg_cd = :sggCd AND umd_cd = :umdCd
              AND jibun IS NOT DISTINCT FROM :jibun AND road_address IS NOT DISTINCT FROM :roadAddress
            """;

    private static final String SAVE_LOCATION = """
            UPDATE apt_complex
            SET location = ST_Transform(ST_SetSRID(ST_MakePoint(:x, :y), %d), 4326),
                geocode_attempted_at = now(), geocode_error = NULL
            WHERE %s
            """.formatted(UtmkPoint.SRID, SAME_ADDRESS);

    private static final String SAVE_FAILURE = """
            UPDATE apt_complex SET geocode_attempted_at = now(), geocode_error = :error
            WHERE %s
            """.formatted(SAME_ADDRESS);

    private final NamedParameterJdbcTemplate jdbc;

    public List<GeocodeTarget> findTargets(int limit, boolean retryFailed) {
        return jdbc.query(FIND_TARGETS,
                new MapSqlParameterSource("limit", limit).addValue("retryFailed", retryFailed),
                (rs, i) -> new GeocodeTarget(rs.getString("apt_seq"), rs.getString("sgg_cd"), rs.getString("umd_cd"),
                        rs.getString("sido_nm"), rs.getString("sgg_nm"), rs.getString("umd_nm"),
                        rs.getString("jibun"), rs.getString("road_address")));
    }

    public long countTargets(boolean retryFailed) {
        return jdbc.queryForObject(COUNT_TARGETS, new MapSqlParameterSource("retryFailed", retryFailed), Long.class);
    }

    /** @return 저장했으면 true, 그사이 주소가 바뀌어 저장하지 않았으면 false */
    public boolean saveLocation(GeocodeTarget target, UtmkPoint point) {
        return jdbc.update(SAVE_LOCATION, address(target).addValue("x", point.x()).addValue("y", point.y())) > 0;
    }

    /** @return 저장했으면 true, 그사이 주소가 바뀌어 저장하지 않았으면 false */
    public boolean saveFailure(GeocodeTarget target, GeocodeError error) {
        return jdbc.update(SAVE_FAILURE, address(target).addValue("error", error.name())) > 0;
    }

    private static MapSqlParameterSource address(GeocodeTarget target) {
        return new MapSqlParameterSource("aptSeq", target.aptSeq())
                .addValue("sggCd", target.sggCd())
                .addValue("umdCd", target.umdCd())
                // null이어도 타입을 알 수 있게 명시한다
                .addValue("jibun", target.jibun(), Types.VARCHAR)
                .addValue("roadAddress", target.roadAddress(), Types.VARCHAR);
    }
}
