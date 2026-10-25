package com.dreamapt.collect;

import static com.dreamapt.collect.TradeFixtures.HARRINGTON;
import static com.dreamapt.collect.TradeFixtures.JUNGGYE;
import static com.dreamapt.collect.TradeFixtures.MYEONGDEOK;
import static com.dreamapt.collect.TradeFixtures.THE_PLATINUM;
import static com.dreamapt.collect.TradeFixtures.TRIZIUM;
import static com.dreamapt.collect.TradeFixtures.existing;
import static com.dreamapt.collect.TradeFixtures.existingAll;
import static com.dreamapt.collect.TradeFixtures.load;
import static com.dreamapt.collect.TradeFixtures.unregistered;
import static com.dreamapt.collect.TradeFixtures.withLateValues;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.dreamapt.collect.ReconcilePlan.Update;

/**
 * 분석 문서(docs/analysis/2026-10-key-collision.md)의 실제 사례로 구성한 대조 시나리오.
 */
class TradeReconcilerTest {

    @Test
    void 해제_후_재신고_명덕() {
        List<TradeRecord> source = load(MYEONGDEOK); // 10-25: 정상(등기) + 해제
        TradeRecord normal = source.get(0);
        TradeRecord cancelled = source.get(1);
        assertThat(normal.cancelled()).isFalse();
        assertThat(cancelled.cancelled()).isTrue();

        // 10-22 수집: 둘 다 등기 전. 기존 정상 행은 그대로, 해제 레코드는 새 행
        ReconcilePlan first = TradeReconciler.plan(List.of(existing(1, unregistered(normal))),
                List.of(unregistered(normal), cancelled));
        assertThat(first.unchanged()).containsExactly(1L);
        assertThat(first.inserts()).containsExactly(cancelled);
        assertThat(first.missing()).isEmpty();

        // 10-25 수집: 정상 레코드에 등기일이 채워짐
        ReconcilePlan second = TradeReconciler.plan(
                List.of(existing(1, unregistered(normal)), existing(2, cancelled)), source);
        assertThat(second.unchanged()).containsExactly(2L);
        assertThat(second.updates()).containsExactly(new Update(1, normal));
        assertThat(second.inserts()).isEmpty();
    }

    @Test
    void 두_번_해제_후_재신고에서_정상_거래가_기존_id를_이어받는다_중계무지개() {
        List<TradeRecord> source = load(JUNGGYE);
        TradeRecord normal = source.stream().filter(r -> !r.cancelled()).findFirst().orElseThrow();
        List<TradeRecord> cancelled = source.stream().filter(TradeRecord::cancelled).toList();

        // 기존 행은 해제 레코드 2건과도 짝지어질 수 있지만, 정상 레코드가 우선한다
        ReconcilePlan plan = TradeReconciler.plan(List.of(existing(1, unregistered(normal))), source);

        assertThat(plan.updates()).containsExactly(new Update(1, normal));
        assertThat(plan.inserts()).containsExactlyInAnyOrderElementsOf(cancelled);
        assertThat(plan.unchanged()).isEmpty();
        assertThat(plan.missing()).isEmpty();
    }

    @Test
    void 일괄_매매_170건이_모두_보존된다_해링턴타워() {
        List<TradeRecord> source = load(HARRINGTON);
        assertThat(source).hasSize(170);

        ReconcilePlan first = TradeReconciler.plan(List.of(), source);
        assertThat(first.inserts()).hasSize(170);

        ReconcilePlan second = TradeReconciler.plan(existingAll(source), source);
        assertThat(second.unchanged()).hasSize(170);
        assertThat(second.updates()).isEmpty();
        assertThat(second.inserts()).isEmpty();
        assertThat(second.missing()).isEmpty();
    }

    @Test
    void 응답_순서가_바뀌어도_계획이_같다() {
        List<TradeRecord> source = Stream.of(HARRINGTON, MYEONGDEOK, JUNGGYE, TRIZIUM)
                .flatMap(name -> load(name).stream())
                .toList();
        List<TradeRecord> reversed = new ArrayList<>(source);
        Collections.reverse(reversed);
        List<TradeRecord> shuffled = new ArrayList<>(source);
        Collections.shuffle(shuffled, new Random(42));

        // 처음 적재: insert 순서까지 같다
        ReconcilePlan original = TradeReconciler.plan(List.of(), source);
        assertThat(TradeReconciler.plan(List.of(), reversed)).isEqualTo(original);
        assertThat(TradeReconciler.plan(List.of(), shuffled)).isEqualTo(original);

        // 재수집: 변경 없음
        List<ExistingTrade> rows = existingAll(source);
        ReconcilePlan again = TradeReconciler.plan(rows, shuffled);
        assertThat(again.unchanged()).hasSize(source.size());
        assertThat(again.updates()).isEmpty();
        assertThat(again.inserts()).isEmpty();
        assertThat(again.missing()).isEmpty();
        assertThat(TradeReconciler.plan(rows, reversed)).isEqualTo(again);
    }

    @Test
    void 등기일과_동이_채워지면_id를_유지한_채_갱신된다_해링턴타워() {
        List<TradeRecord> source = load(HARRINGTON);
        List<ExistingTrade> beforeRegistration = existingAll(source.stream().map(TradeFixtures::unregistered).toList());

        ReconcilePlan plan = TradeReconciler.plan(beforeRegistration, source);

        assertThat(plan.updates()).hasSize(170);
        assertThat(plan.updates()).extracting(Update::id).doesNotHaveDuplicates();
        assertThat(plan.updates()).allSatisfy(u -> {
            assertThat(u.record().registeredDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(u.record().aptDong()).isNotNull();
        });
        assertThat(plan.unchanged()).isEmpty();
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.missing()).isEmpty();
    }

    @Test
    void 같은_키에서_한_건만_등기되면_한_건만_갱신된다_더플래티넘() {
        List<TradeRecord> source = load(THE_PLATINUM);
        TradeRecord registered = source.stream().filter(r -> r.registeredDate() != null).findFirst().orElseThrow();
        List<ExistingTrade> rows = List.of(existing(1, unregistered(source.get(0))),
                existing(2, unregistered(source.get(1))));

        ReconcilePlan plan = TradeReconciler.plan(rows, source);

        assertThat(plan.unchanged()).containsExactly(1L);
        assertThat(plan.updates()).containsExactly(new Update(2, registered));
    }

    @Test
    void 재신고로_거래_유형이_바뀌면_기존_행은_해제되고_새_행이_추가된다_트리지움() {
        List<TradeRecord> source = load(TRIZIUM);
        TradeRecord renewed = source.stream().filter(r -> !r.cancelled()).findFirst().orElseThrow();
        TradeRecord cancelled = source.stream().filter(TradeRecord::cancelled).findFirst().orElseThrow();
        assertThat(renewed.dealingGbn()).isNotEqualTo(cancelled.dealingGbn());

        // 처음 신고(직거래)가 정상이던 시점에 저장된 행
        ExistingTrade original = existing(1, withLateValues(cancelled, null, false, null, null));

        ReconcilePlan plan = TradeReconciler.plan(List.of(original), source);

        assertThat(plan.updates()).containsExactly(new Update(1, cancelled));
        assertThat(plan.inserts()).containsExactly(renewed);
        assertThat(plan.missing()).isEmpty();
    }

    @Test
    void 같은_키_그룹에서_일부만_해제되면_가장_뒤_id가_해제된다_합성() {
        // 해링턴타워 3층 41,350 그룹 (10건, 모든 값이 같음)
        List<TradeRecord> group = load(HARRINGTON).stream()
                .filter(r -> r.floor() == 3 && r.dealAmount() == 41_350)
                .toList();
        assertThat(group).hasSize(10);
        TradeRecord oneCancelled = withLateValues(group.get(0), group.get(0).aptDong(), true,
                LocalDate.of(2026, 10, 20), group.get(0).registeredDate());
        List<TradeRecord> source = new ArrayList<>(group.subList(1, 10));
        source.add(oneCancelled);

        ReconcilePlan plan = TradeReconciler.plan(existingAll(group), source);

        assertThat(plan.unchanged()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(plan.updates()).containsExactly(new Update(10, oneCancelled));
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.missing()).isEmpty();
    }

    @Test
    void 원본에서_사라진_행은_missing이고_다시_나타나면_표시를_지운다() {
        List<TradeRecord> source = load(MYEONGDEOK);
        TradeRecord normal = source.get(0);
        TradeRecord cancelled = source.get(1);

        ReconcilePlan gone = TradeReconciler.plan(existingAll(source), List.of(normal));
        assertThat(gone.unchanged()).containsExactly(1L);
        assertThat(gone.missing()).extracting(ExistingTrade::id).containsExactly(2L);
        assertThat(gone.newlyMissing()).hasSize(1);

        OffsetDateTime since = OffsetDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneOffset.ofHours(9));
        List<ExistingTrade> rows = List.of(existing(1, normal), existing(2, cancelled, since));

        // 계속 없으면 missing이지만 새로 표시할 대상은 아니다
        assertThat(TradeReconciler.plan(rows, List.of(normal)).newlyMissing()).isEmpty();

        ReconcilePlan back = TradeReconciler.plan(rows, source);
        assertThat(back.unchanged()).containsExactly(1L);
        assertThat(back.updates()).containsExactly(new Update(2, cancelled));
        assertThat(back.missing()).isEmpty();
    }

    @Test
    void 탐욕_매칭으로는_짝을_놓치는_경우도_최대_매칭으로_모두_짝짓는다_합성() {
        TradeRecord base = unregistered(load(MYEONGDEOK).get(0));
        LocalDate rgst = LocalDate.of(2026, 10, 20);
        ExistingTrade noDong = existing(1, base);
        ExistingTrade dong101 = existing(2, withLateValues(base, "101", false, null, null));
        TradeRecord registered101 = withLateValues(base, "101", false, null, rgst);
        TradeRecord registered102 = withLateValues(base, "102", false, null, rgst);

        // 101 레코드가 먼저 동 없는 행을 가져가면 101 행은 짝을 잃는다. 증가 경로로 옮겨서 둘 다 짝짓는다
        ReconcilePlan plan = TradeReconciler.plan(List.of(noDong, dong101), List.of(registered101, registered102));

        assertThat(plan.updates()).containsExactlyInAnyOrder(new Update(1, registered102), new Update(2, registered101));
        assertThat(plan.inserts()).isEmpty();
        assertThat(plan.missing()).isEmpty();
    }

    @Test
    void 채워진_값이_바뀌거나_해제가_정상으로_돌아가는_것은_갱신으로_보지_않는다() {
        TradeRecord base = load(MYEONGDEOK).get(0);
        TradeRecord cancelledRecord = load(MYEONGDEOK).get(1);

        assertThat(TradeReconciler.canFill(existing(1, withLateValues(base, "101", false, null, null)),
                withLateValues(base, "102", false, null, null))).isFalse();
        assertThat(TradeReconciler.canFill(existing(1, cancelledRecord), unregistered(base))).isFalse();
        assertThat(TradeReconciler.canFill(existing(1, unregistered(base)), cancelledRecord)).isTrue();
    }
}
