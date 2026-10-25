package com.dreamapt.collect.batch;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 모드별 대상 구×월 계산 (ADR 005). 날짜가 아니라 {@link YearMonth}로 계산해서 말일·윤년과 무관하다.
 * 순서는 최근 월 우선, 같은 월 안에서는 구 코드 순이다. backfill이 중간에 멈춰도 최근 데이터부터 채워진다.
 */
public final class CollectTargetPlanner {

    /** 일일: 이번 달 포함 최근 6개월 (M ~ M-5) */
    static final int DAILY_MONTHS = 6;
    /** 주간: 7–12개월 전 (M-6 ~ M-11) */
    static final int WEEKLY_MONTHS = 6;

    private CollectTargetPlanner() {
    }

    /**
     * @param baseDate      기준일 (Asia/Seoul 날짜). 이 날짜가 속한 달이 M이다
     * @param sggCds        수집 대상 구 (정렬된 상태로 넘긴다)
     * @param backfillStart backfill 시작 월. backfill이 아니면 쓰지 않는다
     */
    public static List<CollectTarget> plan(CollectMode mode, LocalDate baseDate, List<String> sggCds,
            YearMonth backfillStart) {
        YearMonth current = YearMonth.from(baseDate);
        return switch (mode) {
            case DAILY -> targets(current, current.minusMonths(DAILY_MONTHS - 1), sggCds);
            case WEEKLY -> targets(current.minusMonths(DAILY_MONTHS),
                    current.minusMonths(DAILY_MONTHS + WEEKLY_MONTHS - 1), sggCds);
            case BACKFILL -> targets(current, backfillStart, sggCds);
        };
    }

    /** latest부터 earliest까지 거꾸로 (earliest가 더 늦으면 빈 목록) */
    private static List<CollectTarget> targets(YearMonth latest, YearMonth earliest, List<String> sggCds) {
        List<CollectTarget> targets = new ArrayList<>();
        for (YearMonth month = latest; !month.isBefore(earliest); month = month.minusMonths(1)) {
            for (String sggCd : sggCds) {
                targets.add(new CollectTarget(sggCd, month));
            }
        }
        return targets;
    }
}
