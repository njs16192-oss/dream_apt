package com.dreamapt.collect.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.batch.test.MetaDataInstanceFactory;

import com.dreamapt.collect.ApiRequestThrottle;
import com.dreamapt.collect.CollectResult;
import com.dreamapt.collect.DataGoKrProperties;
import com.dreamapt.collect.TradeApiException;
import com.dreamapt.collect.TradeApiException.Kind;
import com.dreamapt.region.Region;
import com.dreamapt.region.RegionRepository;

/**
 * 실패 격리, 실행 중단, backfill 건너뛰기·호출 상한 (ADR 006 3-①~③).
 * 수집은 가짜 runner가 대신하고, 호출 수는 실제 throttle로 센다.
 */
class CollectTargetsTaskletTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    // 서울 2026-10-25 09:00 (UTC로는 10-25 00:00)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), SEOUL);

    private final RegionRepository regionRepository = mock(RegionRepository.class);
    private final CollectHistoryRepository historyRepository = mock(CollectHistoryRepository.class);
    private final ApiRequestThrottle throttle = new ApiRequestThrottle(new DataGoKrProperties(null, null, Duration.ZERO));
    private final FakeRunner runner = new FakeRunner(throttle);

    @BeforeEach
    void setUp() {
        List<Region> regions = regions("11350", "11500");
        given(regionRepository.findByCollectEnabledTrueOrderBySggCd()).willReturn(regions);
        given(historyRepository.findSucceeded(anyCollection())).willReturn(Set.of());
    }

    @Test
    void 구월_하나가_실패해도_나머지를_끝까지_수집한다() {
        runner.failWith(target("11350", 2026, 9), new TradeApiException("원본 불완전"));
        runner.failWith(target("11500", 2026, 7), new IllegalStateException("DB 오류"));

        Result result = run(tasklet(CollectMode.DAILY, 1000));

        assertThat(runner.attempted).hasSize(12);
        assertThat(result.status).isEqualTo(BatchStatus.COMPLETED);
        assertThat(result.exitStatus.getExitCode()).isEqualTo(CollectExitStatus.COMPLETED_WITH_FAILURES);
        assertThat(result.summary()).containsEntry("summary.succeeded", 10).containsEntry("summary.failed", 2)
                .containsEntry("summary.remaining", 0);
    }

    @Test
    void 모두_성공하면_ExitStatus를_바꾸지_않는다() {
        Result result = run(tasklet(CollectMode.WEEKLY, 1000));

        assertThat(runner.attempted).hasSize(12);
        assertThat(result.exitStatus).isEqualTo(ExitStatus.COMPLETED);
    }

    @Test
    void 기준일이_없으면_서울_기준_오늘이다() {
        // UTC로는 2026-09-30 15:00이지만 서울은 10-01 00:00 → 이번 달은 10월
        Clock monthStart = Clock.fixed(Instant.parse("2026-09-30T15:00:00Z"), SEOUL);

        run(tasklet(CollectMode.DAILY, null, 1000, monthStart));

        assertThat(runner.attempted.get(0)).isEqualTo(target("11350", 2026, 10));
    }

    @Test
    void 한도_초과면_남은_구월을_처리하지_않고_실패로_끝낸다() {
        runner.failWith(target("11500", 2026, 10), new TradeApiException(Kind.QUOTA_EXCEEDED, "한도 초과"));

        Result result = run(tasklet(CollectMode.DAILY, 1000));

        // 11350×202610, 11500×202610 까지만 시도
        assertThat(runner.attempted).hasSize(2);
        assertThat(result.status).isEqualTo(BatchStatus.FAILED);
        assertThat(result.exitStatus.getExitCode()).isEqualTo(ExitStatus.FAILED.getExitCode());
        assertThat(result.summary()).containsEntry("summary.remaining", 10);
    }

    @Test
    void backfill은_이미_성공한_구월을_건너뛴다() {
        given(historyRepository.findSucceeded(anyCollection()))
                .willReturn(Set.of(target("11350", 2026, 10), target("11500", 2026, 9)));

        Result result = run(tasklet(CollectMode.BACKFILL, 1000));

        // backfill 시작 2026-09 → 2개월 × 2개 구 = 4, 그중 2개는 이미 성공
        assertThat(runner.attempted).containsExactly(target("11500", 2026, 10), target("11350", 2026, 9));
        assertThat(result.summary()).containsEntry("summary.skipped", 2);
    }

    @Test
    void backfill은_호출_상한에_닿으면_멈추고_남은_수를_남긴다() {
        Result result = run(tasklet(CollectMode.BACKFILL, 3));

        assertThat(runner.attempted).hasSize(3);
        assertThat(result.exitStatus).isEqualTo(ExitStatus.COMPLETED);
        assertThat(result.summary()).containsEntry("summary.remaining", 1)
                .containsEntry("summary.callLimitReached", true)
                .containsEntry("summary.apiCalls", 3L);
    }

    @Test
    void 호출_상한은_backfill에만_적용된다() {
        run(tasklet(CollectMode.DAILY, 3));

        assertThat(runner.attempted).hasSize(12);
        verify(historyRepository, never()).findSucceeded(any());
    }

    private CollectTargetsTasklet tasklet(CollectMode mode, int maxCalls) {
        return tasklet(mode, LocalDate.of(2026, 10, 25), maxCalls, CLOCK);
    }

    private CollectTargetsTasklet tasklet(CollectMode mode, LocalDate baseDate, int maxCalls, Clock clock) {
        CollectBatchProperties properties = new CollectBatchProperties(SEOUL, YearMonth.of(2026, 9), maxCalls,
                Duration.ofHours(2), new CollectBatchProperties.Retry(0, Duration.ofMillis(1), 1));
        return new CollectTargetsTasklet(mode, baseDate, regionRepository, historyRepository, runner, throttle,
                properties, clock);
    }

    /** step이 하는 일을 흉내 낸다: beforeStep → FINISHED나 예외까지 execute → afterStep */
    private static Result run(CollectTargetsTasklet tasklet) {
        StepExecution stepExecution = MetaDataInstanceFactory.createStepExecution();
        tasklet.beforeStep(stepExecution);
        try {
            RepeatStatus status;
            do {
                status = tasklet.execute(new StepContribution(stepExecution),
                        new ChunkContext(new StepContext(stepExecution)));
            } while (status.isContinuable());
            stepExecution.setStatus(BatchStatus.COMPLETED);
            stepExecution.setExitStatus(ExitStatus.COMPLETED);
        } catch (RuntimeException e) {
            stepExecution.setStatus(BatchStatus.FAILED);
            stepExecution.setExitStatus(ExitStatus.FAILED);
        }
        ExitStatus changed = tasklet.afterStep(stepExecution);
        return new Result(stepExecution.getStatus(), changed != null ? changed : stepExecution.getExitStatus(),
                stepExecution);
    }

    private record Result(BatchStatus status, ExitStatus exitStatus, StepExecution stepExecution) {
        Map<String, Object> summary() {
            return Map.copyOf(stepExecution.getExecutionContext().toMap());
        }
    }

    private static CollectTarget target(String sggCd, int year, int month) {
        return new CollectTarget(sggCd, YearMonth.of(year, month));
    }

    private static List<Region> regions(String... sggCds) {
        return Stream.of(sggCds).map(sggCd -> {
            Region region = mock(Region.class);
            given(region.getSggCd()).willReturn(sggCd);
            return region;
        }).toList();
    }

    /** 대상마다 API를 한 번 부르는 것으로 치고, 지정한 대상은 실패시킨다 */
    private static class FakeRunner extends CollectTargetRunner {

        private final ApiRequestThrottle throttle;
        private final Map<CollectTarget, RuntimeException> failures = new HashMap<>();
        final List<CollectTarget> attempted = new ArrayList<>();

        FakeRunner(ApiRequestThrottle throttle) {
            super(null, null, null, null, null);
            this.throttle = throttle;
        }

        void failWith(CollectTarget target, RuntimeException e) {
            failures.put(target, e);
        }

        @Override
        public CollectResult collect(CollectTarget target, Long jobExecutionId) {
            attempted.add(target);
            throttle.acquire();
            RuntimeException failure = failures.get(target);
            if (failure != null) {
                throw failure;
            }
            return new CollectResult(target.sggCd(), target.dealYm(), 0, 0, 0, 0, 0, 0, List.of());
        }
    }
}
