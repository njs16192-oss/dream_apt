package com.dreamapt.collect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 구×월 응답과 DB 행을 같은 키 그룹 안에서 대조한다 (ADR 004). DB에 접근하지 않는 순수 로직이다.
 *
 * <p>같은 키의 레코드는 어떤 필드로도 구분할 수 없으므로 "어느 레코드가 어느 행인가"는 맞히지 않는다.
 * 그룹 안의 건수와 상태가 원본과 같아지도록 짝을 짓는다.
 * <ol>
 *   <li>모든 값이 같은 쌍 → unchanged</li>
 *   <li>차이가 "빈 값 → 채워짐"으로 설명되는 쌍 → update (최대 이분 매칭)</li>
 *   <li>남은 응답 레코드 → insert</li>
 *   <li>남은 DB 행 → missing</li>
 * </ol>
 * 같은 입력이면 응답 순서와 관계없이 항상 같은 계획이 나오도록 양쪽을 정렬한 뒤 짝짓는다.
 */
public final class TradeReconciler {

    /**
     * DB 행 짝짓기 순서: missing이 아닌 행 먼저 (표시 변경을 줄인다), 그다음 id 순
     */
    private static final Comparator<ExistingTrade> EXISTING_ORDER = Comparator
            .comparing(ExistingTrade::missing)
            .thenComparingLong(ExistingTrade::id);

    /**
     * 응답 레코드 짝짓기 순서: 정상 거래 먼저 (해제 후 재신고에서 정상 거래가 기존 id를 이어받는다),
     * 그다음 값 순. 값이 모두 같은 레코드끼리는 서로 바뀌어도 결과가 같다.
     */
    private static final Comparator<TradeRecord> RECORD_ORDER = Comparator
            .comparing(TradeRecord::cancelled)
            .thenComparing(TradeRecord::aptDong, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TradeRecord::registeredDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TradeRecord::cancelDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TradeRecord::buyerGbn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TradeRecord::sellerGbn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TradeRecord::dealingGbn, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TradeRecord::agentSggNm, Comparator.nullsLast(Comparator.naturalOrder()));

    private TradeReconciler() {
    }

    public static ReconcilePlan plan(List<ExistingTrade> existing, List<TradeRecord> incoming) {
        // 그룹을 키 순서로 처리해야 계획의 목록 순서도 입력 순서와 무관해진다
        Map<TradeKey, Group> groups = new TreeMap<>();
        existing.forEach(t -> groups.computeIfAbsent(TradeKey.of(t), k -> new Group()).existing.add(t));
        incoming.forEach(r -> groups.computeIfAbsent(TradeKey.of(r), k -> new Group()).incoming.add(r));

        ReconcilePlan plan = new ReconcilePlan(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>());
        groups.values().forEach(g -> g.reconcile(plan));
        return plan;
    }

    private static final class Group {

        final List<ExistingTrade> existing = new ArrayList<>();
        final List<TradeRecord> incoming = new ArrayList<>();

        void reconcile(ReconcilePlan plan) {
            existing.sort(EXISTING_ORDER);
            incoming.sort(RECORD_ORDER);

            // 1. 모든 값이 같은 쌍
            List<ExistingTrade> rows = new ArrayList<>(existing);
            List<TradeRecord> records = new ArrayList<>();
            for (TradeRecord r : incoming) {
                ExistingTrade same = rows.stream().filter(t -> sameValues(t, r)).findFirst().orElse(null);
                if (same == null) {
                    records.add(r);
                } else {
                    rows.remove(same);
                    if (same.missing()) {
                        plan.updates().add(new ReconcilePlan.Update(same.id(), r));
                    } else {
                        plan.unchanged().add(same.id());
                    }
                }
            }

            // 2. "빈 값 → 채워짐"으로 설명되는 쌍. 1단계를 먼저 해도 최대 매칭 크기는 줄지 않는다 (ADR 004 3-②)
            int[] matchedRecord = maxMatching(rows, records);
            boolean[] recordMatched = new boolean[records.size()];
            for (int j = 0; j < rows.size(); j++) {
                if (matchedRecord[j] < 0) {
                    plan.missing().add(rows.get(j)); // 4. 짝이 없는 DB 행
                } else {
                    recordMatched[matchedRecord[j]] = true;
                    plan.updates().add(new ReconcilePlan.Update(rows.get(j).id(), records.get(matchedRecord[j])));
                }
            }
            // 3. 짝이 없는 응답 레코드
            for (int i = 0; i < records.size(); i++) {
                if (!recordMatched[i]) {
                    plan.inserts().add(records.get(i));
                }
            }
        }
    }

    /**
     * 최대 이분 매칭 (Kuhn 알고리즘). 레코드를 정렬 순서대로 하나씩 넣으면서, 짝이 이미 있는 행은
     * 그 행의 기존 짝을 다른 행으로 옮길 수 있으면(증가 경로) 옮긴다. 먼저 짝지어진 레코드는
     * 이후에도 짝을 잃지 않으므로 정렬 순서가 앞선 레코드가 우선한다.
     *
     * @return 행 인덱스별로 짝지어진 레코드 인덱스, 짝이 없으면 -1
     */
    private static int[] maxMatching(List<ExistingTrade> rows, List<TradeRecord> records) {
        int[] matchedRecord = new int[rows.size()];
        Arrays.fill(matchedRecord, -1);
        for (int i = 0; i < records.size(); i++) {
            augment(i, rows, records, matchedRecord, new boolean[rows.size()]);
        }
        return matchedRecord;
    }

    private static boolean augment(int i, List<ExistingTrade> rows, List<TradeRecord> records,
            int[] matchedRecord, boolean[] visited) {
        for (int j = 0; j < rows.size(); j++) {
            if (visited[j] || !canFill(rows.get(j), records.get(i))) {
                continue;
            }
            visited[j] = true;
            if (matchedRecord[j] < 0 || augment(matchedRecord[j], rows, records, matchedRecord, visited)) {
                matchedRecord[j] = i;
                return true;
            }
        }
        return false;
    }

    static boolean sameValues(ExistingTrade t, TradeRecord r) {
        return sameImmutable(t, r)
                && Objects.equals(t.aptDong(), r.aptDong())
                && Objects.equals(t.registeredDate(), r.registeredDate())
                && t.cancelled() == r.cancelled()
                && Objects.equals(t.cancelDate(), r.cancelDate());
    }

    /**
     * 행 t가 레코드 r로 "빈 값 → 채워짐"만으로 바뀔 수 있는가 (ADR 004의 t ≤ r).
     * 이미 채워진 값이 바뀌거나 비는 경우, 해제 → 정상은 인정하지 않는다.
     */
    static boolean canFill(ExistingTrade t, TradeRecord r) {
        return sameImmutable(t, r)
                && fills(t.aptDong(), r.aptDong())
                && fills(t.registeredDate(), r.registeredDate())
                && (!t.cancelled() || r.cancelled())
                && fills(t.cancelDate(), r.cancelDate());
    }

    private static boolean sameImmutable(ExistingTrade t, TradeRecord r) {
        return Objects.equals(t.buyerGbn(), r.buyerGbn())
                && Objects.equals(t.sellerGbn(), r.sellerGbn())
                && Objects.equals(t.dealingGbn(), r.dealingGbn())
                && Objects.equals(t.agentSggNm(), r.agentSggNm());
    }

    private static boolean fills(Object before, Object after) {
        return before == null || before.equals(after);
    }

    /**
     * 같은 거래 그룹의 키. 면적은 scale이 다르면 equals가 달라지므로 소수 둘째 자리로 맞춘다.
     */
    record TradeKey(String aptSeq, LocalDate dealDate, BigDecimal excluUseAr, short floor, int dealAmount)
            implements Comparable<TradeKey> {

        private static final Comparator<TradeKey> ORDER = Comparator.comparing(TradeKey::aptSeq)
                .thenComparing(TradeKey::dealDate)
                .thenComparing(TradeKey::excluUseAr)
                .thenComparingInt(TradeKey::floor)
                .thenComparingInt(TradeKey::dealAmount);

        static TradeKey of(TradeRecord r) {
            return new TradeKey(r.aptSeq(), r.dealDate(), scale(r.excluUseAr()), r.floor(), r.dealAmount());
        }

        static TradeKey of(ExistingTrade t) {
            return new TradeKey(t.aptSeq(), t.dealDate(), scale(t.excluUseAr()), t.floor(), t.dealAmount());
        }

        private static BigDecimal scale(BigDecimal area) {
            return area.setScale(2, RoundingMode.HALF_UP);
        }

        @Override
        public int compareTo(TradeKey o) {
            return ORDER.compare(this, o);
        }
    }
}
