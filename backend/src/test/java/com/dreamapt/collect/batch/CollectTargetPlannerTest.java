package com.dreamapt.collect.batch;

import static com.dreamapt.collect.batch.CollectMode.BACKFILL;
import static com.dreamapt.collect.batch.CollectMode.DAILY;
import static com.dreamapt.collect.batch.CollectMode.WEEKLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CollectTargetPlannerTest {

    private static final List<String> SGG = List.of("11350", "11500");
    private static final YearMonth BACKFILL_START = YearMonth.of(2020, 1);

    @Test
    void 일일은_이번_달_포함_최근_6개월이다() {
        List<CollectTarget> targets = plan(DAILY, LocalDate.of(2026, 10, 25));

        assertThat(months(targets)).containsExactly("202610", "202609", "202608", "202607", "202606", "202605");
        assertThat(targets).hasSize(6 * SGG.size());
    }

    @Test
    void 주간은_7개월_전부터_12개월_전까지다() {
        assertThat(months(plan(WEEKLY, LocalDate.of(2026, 10, 25))))
                .containsExactly("202604", "202603", "202602", "202601", "202512", "202511");
    }

    @Test
    void 일일과_주간을_이으면_빈틈과_겹침_없이_최근_12개월이다() {
        LocalDate base = LocalDate.of(2026, 10, 25);
        List<String> joined = new ArrayList<>(months(plan(DAILY, base)));
        joined.addAll(months(plan(WEEKLY, base)));

        // backfill의 앞 12개월과 같다
        assertThat(joined).containsExactlyElementsOf(months(plan(BACKFILL, base)).subList(0, 12));
    }

    @Test
    void 연_경계를_넘어간다() {
        LocalDate base = LocalDate.of(2026, 1, 15);

        assertThat(months(plan(DAILY, base)))
                .containsExactly("202601", "202512", "202511", "202510", "202509", "202508");
        assertThat(months(plan(WEEKLY, base)))
                .containsExactly("202507", "202506", "202505", "202504", "202503", "202502");
    }

    // 기준일이 월초·말일·윤년 2월 말이어도 그 달이 M이다
    @ParameterizedTest
    @CsvSource({
            "2026-03-01, 202603, 202510",
            "2026-03-31, 202603, 202510",
            "2028-02-29, 202802, 202709",
            "2026-12-31, 202612, 202607",
    })
    void 월_경계에서도_기준일이_속한_달이_M이다(LocalDate base, String firstDaily, String lastDaily) {
        List<String> daily = months(plan(DAILY, base));

        assertThat(daily).first().isEqualTo(firstDaily);
        assertThat(daily).last().isEqualTo(lastDaily);
        assertThat(daily).hasSize(6);
    }

    @Test
    void backfill은_시작_월부터_이번_달까지_최근_월부터다() {
        List<CollectTarget> targets = plan(BACKFILL, LocalDate.of(2026, 10, 25));

        // 2020-01 ~ 2026-10 = 82개월
        assertThat(targets).hasSize(82 * SGG.size());
        assertThat(targets.get(0)).isEqualTo(new CollectTarget("11350", YearMonth.of(2026, 10)));
        assertThat(targets.get(targets.size() - 1)).isEqualTo(new CollectTarget("11500", YearMonth.of(2020, 1)));
    }

    @Test
    void 같은_월_안에서는_구_코드_순이다() {
        assertThat(plan(DAILY, LocalDate.of(2026, 10, 25))).startsWith(
                new CollectTarget("11350", YearMonth.of(2026, 10)),
                new CollectTarget("11500", YearMonth.of(2026, 10)),
                new CollectTarget("11350", YearMonth.of(2026, 9)));
    }

    @Test
    void backfill_시작_월이_이번_달보다_늦으면_대상이_없다() {
        assertThat(CollectTargetPlanner.plan(BACKFILL, LocalDate.of(2019, 12, 31), SGG, BACKFILL_START)).isEmpty();
    }

    @Test
    void 모드_이름은_대소문자를_가리지_않는다() {
        assertThat(CollectMode.from("daily")).isEqualTo(DAILY);
        assertThat(CollectMode.from(" Backfill ")).isEqualTo(BACKFILL);
        assertThatThrownBy(() -> CollectMode.from("monthly")).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<CollectTarget> plan(CollectMode mode, LocalDate base) {
        return CollectTargetPlanner.plan(mode, base, SGG, BACKFILL_START);
    }

    /** 대상의 월을 중복 없이 순서대로 */
    private static List<String> months(List<CollectTarget> targets) {
        return targets.stream().map(CollectTarget::dealYm).distinct().toList();
    }
}
