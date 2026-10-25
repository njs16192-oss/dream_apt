package com.dreamapt.collect.batch;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.dreamapt.collect.CollectResult;

import lombok.RequiredArgsConstructor;

/**
 * 구×월별 최신 수집 상태 (collect_history, ADR 006 3-③). 구×월당 1행을 upsert 한다.
 * 실패하면 상태·사유·시도 시각만 바꾸고, 마지막 성공 시각과 결과 건수는 유지한다.
 */
@Repository
@RequiredArgsConstructor
public class CollectHistoryRepository {

    private static final DateTimeFormatter DEAL_YM = DateTimeFormatter.ofPattern("yyyyMM");

    private static final String UPSERT_SUCCESS = """
            INSERT INTO collect_history (sgg_cd, deal_ym, status, last_attempted_at, last_succeeded_at,
                fetched, inserted, updated, unchanged, missing, warnings, failure_reason, api_calls, job_execution_id)
            VALUES (:sggCd, :dealYm, 'SUCCESS', :at, :at,
                :fetched, :inserted, :updated, :unchanged, :missing, :warnings, NULL, :apiCalls, :jobExecutionId)
            ON CONFLICT (sgg_cd, deal_ym) DO UPDATE SET
                status = 'SUCCESS',
                last_attempted_at = EXCLUDED.last_attempted_at,
                last_succeeded_at = EXCLUDED.last_succeeded_at,
                fetched = EXCLUDED.fetched,
                inserted = EXCLUDED.inserted,
                updated = EXCLUDED.updated,
                unchanged = EXCLUDED.unchanged,
                missing = EXCLUDED.missing,
                warnings = EXCLUDED.warnings,
                failure_reason = NULL,
                api_calls = EXCLUDED.api_calls,
                job_execution_id = EXCLUDED.job_execution_id
            """;

    private static final String UPSERT_FAILURE = """
            INSERT INTO collect_history (sgg_cd, deal_ym, status, last_attempted_at, failure_reason, api_calls,
                job_execution_id)
            VALUES (:sggCd, :dealYm, 'FAILED', :at, :failureReason, :apiCalls, :jobExecutionId)
            ON CONFLICT (sgg_cd, deal_ym) DO UPDATE SET
                status = 'FAILED',
                last_attempted_at = EXCLUDED.last_attempted_at,
                failure_reason = EXCLUDED.failure_reason,
                api_calls = EXCLUDED.api_calls,
                job_execution_id = EXCLUDED.job_execution_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public void recordSuccess(CollectTarget target, CollectResult result, int apiCalls, Long jobExecutionId,
            OffsetDateTime at) {
        jdbc.update(UPSERT_SUCCESS, key(target, apiCalls, jobExecutionId, at)
                .addValue("fetched", result.fetched())
                .addValue("inserted", result.inserted())
                .addValue("updated", result.updated())
                .addValue("unchanged", result.unchanged())
                .addValue("missing", result.missing())
                .addValue("warnings", result.warnings().toArray(String[]::new)));
    }

    public void recordFailure(CollectTarget target, String failureReason, int apiCalls, Long jobExecutionId,
            OffsetDateTime at) {
        jdbc.update(UPSERT_FAILURE, key(target, apiCalls, jobExecutionId, at)
                .addValue("failureReason", failureReason));
    }

    /**
     * 주어진 구들의 구×월 중 마지막 시도가 성공인 것. backfill이 건너뛸 대상이다.
     * 한 번 성공한 뒤 재수집이 실패한 구×월은 포함하지 않는다 (다시 시도한다).
     */
    public Set<CollectTarget> findSucceeded(Collection<String> sggCds) {
        if (sggCds.isEmpty()) {
            return Set.of();
        }
        Set<CollectTarget> succeeded = new HashSet<>();
        jdbc.query("SELECT sgg_cd, deal_ym FROM collect_history WHERE status = 'SUCCESS' AND sgg_cd IN (:sggCds)",
                Map.of("sggCds", sggCds),
                rs -> {
                    succeeded.add(new CollectTarget(rs.getString("sgg_cd"),
                            YearMonth.parse(rs.getString("deal_ym"), DEAL_YM)));
                });
        return succeeded;
    }

    private static MapSqlParameterSource key(CollectTarget target, int apiCalls, Long jobExecutionId,
            OffsetDateTime at) {
        return new MapSqlParameterSource()
                .addValue("sggCd", target.sggCd())
                .addValue("dealYm", target.dealYm())
                .addValue("at", at)
                .addValue("apiCalls", apiCalls)
                .addValue("jobExecutionId", jobExecutionId);
    }
}
