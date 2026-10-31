package com.dreamapt.collect.batch;

import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 수집 배치 설정 (ADR 005, 006).
 *
 * @param zone              기준일을 정하는 시간대. k8s 노드는 UTC라 명시한다
 * @param backfillStart     backfill 시작 월
 * @param backfillMaxCalls  backfill 1회 실행당 최대 API 호출 수. 다음 구×월 시작 전에 확인한다
 * @param staleRunningAfter 시작 후 이 시간이 지난 STARTED 실행은 실행 중으로 보지 않는다
 * @param retry             일시 오류 재시도
 * @param geocodeLimit      수집 뒤 지오코딩 step이 1회 실행에 처리할 최대 단지 수. 0이면 끈다 (ADR 010)
 */
@ConfigurationProperties(prefix = "dreamapt.collect.batch")
public record CollectBatchProperties(
        ZoneId zone,
        YearMonth backfillStart,
        int backfillMaxCalls,
        Duration staleRunningAfter,
        Retry retry,
        int geocodeLimit) {

    /**
     * @param maxRetries 첫 시도 뒤 최대 재시도 횟수
     * @param delay      첫 재시도 전 대기. 이후 multiplier 배씩 늘어난다
     */
    public record Retry(int maxRetries, Duration delay, double multiplier) {
    }
}
