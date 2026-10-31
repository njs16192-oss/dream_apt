package com.dreamapt.collect;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 수집한 구×월 거래를 DB와 대조해서 저장한다 (ADR 004).
 * {@link TradeCollectService}와 분리한 이유: 같은 클래스 안에서 호출하면 프록시를 거치지 않아
 * {@code @Transactional}이 적용되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeReconcileService {

    private static final DateTimeFormatter DEAL_YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final TradeReconcileRepository repository;
    private final CollectProperties properties;

    /**
     * 단지를 먼저(FK) upsert 하고, 거래를 대조 결과대로 쓴다. 하나라도 실패하면 전체 롤백.
     *
     * @param records 해당 구×월 응답 전체. 일부만 넘기면 나머지가 missing으로 표시된다
     */
    @Transactional
    public ReconcileResult save(String sggCd, String dealYm, List<TradeRecord> records) {
        if (records.isEmpty()) {
            // 장애·일시 오류로 그 달 전체가 missing이 되는 것을 막는다
            log.warn("응답이 0건이라 대조하지 않음 sggCd={} dealYm={}", sggCd, dealYm);
            return ReconcileResult.EMPTY;
        }
        YearMonth month = YearMonth.parse(dealYm, DEAL_YM);
        checkInMonth(sggCd, month, records);

        repository.lockMonth(sggCd, dealYm);

        // 같은 단지가 거래 수만큼 반복되므로 aptSeq 기준으로 하나만 남긴다. 단지 정보는 계약일이 가장 늦은 거래의 것을 쓴다
        // (같은 달 안에서도 주소가 다른 거래가 있다). 날짜가 같으면 먼저 나온 거래. 해제된 거래도 빼지 않는다:
        // 해제 여부와 관계없이 그 거래에 적힌 주소는 그 시점의 주소다 (ADR 003)
        // upsert는 단지 행을 잠그므로, 같은 단지를 포함한 다른 구×월 수집과 교착되지 않게 aptSeq 순서로 쓴다
        Map<String, TradeRecord> complexes = new TreeMap<>();
        records.forEach(r -> complexes.merge(r.aptSeq(), r,
                (kept, next) -> next.dealDate().isAfter(kept.dealDate()) ? next : kept));
        repository.upsertComplexes(complexes.values());

        List<ExistingTrade> existing = repository.findTradesInMonth(sggCd, month.atDay(1), month.atEndOfMonth());
        ReconcilePlan plan = TradeReconciler.plan(existing, records);

        repository.insertTrades(plan.inserts());
        repository.updateTrades(plan.updates());

        List<String> warnings = new ArrayList<>();
        List<ExistingTrade> newlyMissing = plan.newlyMissing();
        long valid = existing.stream().filter(t -> !t.missing()).count();
        if (exceedsMissingLimit(newlyMissing.size(), valid)) {
            log.warn("새 missing이 너무 많아 표시를 보류함 sggCd={} dealYm={} newlyMissing={} valid={}",
                    sggCd, dealYm, newlyMissing.size(), valid);
            warnings.add(ReconcileResult.MISSING_RATIO_EXCEEDED);
        } else {
            repository.markMissing(newlyMissing.stream().map(ExistingTrade::id).toList());
        }

        return new ReconcileResult(complexes.size(), plan.inserts().size(), plan.updates().size(),
                plan.unchanged().size(), plan.missing().size(), List.copyOf(warnings));
    }

    /**
     * 보류 조건: 새 missing ≥ 최소 건수 그리고 새 missing / 기존 유효 행 > 비율 한도.
     * 최소 건수가 없으면 작은 구×월은 실제로 사라진 거래도 매번 비율에 걸려 영원히 보류된다.
     */
    private boolean exceedsMissingLimit(int newlyMissing, long valid) {
        return valid > 0
                && newlyMissing >= properties.missingMinCount()
                && (double) newlyMissing / valid > properties.missingRatioLimit();
    }

    /**
     * 대조 범위(구×월)는 DB 조회 조건이기도 하다. 범위 밖 레코드가 섞이면 기존 행을 찾지 못해
     * 수집할 때마다 중복 insert 되므로 저장하지 않는다.
     */
    private static void checkInMonth(String sggCd, YearMonth month, List<TradeRecord> records) {
        records.stream()
                .filter(r -> !sggCd.equals(r.sggCd()) || !YearMonth.from(r.dealDate()).equals(month))
                .findFirst()
                .ifPresent(r -> {
                    throw new TradeApiException("요청 범위 밖 레코드: sggCd=" + sggCd + ", dealYm=" + month
                            + ", record=" + r.aptSeq() + " " + r.dealDate());
                });
    }
}
