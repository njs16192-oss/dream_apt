package com.dreamapt.collect;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 수집한 거래를 DB에 저장한다.
 * {@link TradeCollectService}와 분리한 이유: 같은 클래스 안에서 호출하면 프록시를 거치지 않아
 * {@code @Transactional}이 적용되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeUpsertService {

    /**
     * 같은 키 레코드 중 남길 것을 고르는 순서. API 응답 순서와 무관하게 항상 같은 레코드가 선택되도록
     * 값으로만 비교한다. 정상 거래를 해제 거래보다 우선한다 (해제 후 재신고 사례, docs/analysis/2026-10-key-collision.md).
     */
    private static final Comparator<TradeRecord> MERGE_PRIORITY = Comparator
            .comparing(TradeRecord::cancelled)
            .thenComparing(TradeRecord::registeredDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TradeRecord::aptDong, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(TradeRecord::cancelDate, Comparator.nullsLast(Comparator.naturalOrder()));

    private final TradeUpsertRepository upsertRepository;

    /**
     * 단지를 먼저(FK), 거래를 나중에 upsert 한다. 하나라도 실패하면 전체 롤백.
     */
    @Transactional
    public UpsertResult save(List<TradeRecord> records) {
        if (records.isEmpty()) {
            return UpsertResult.EMPTY;
        }

        List<TradeRecord> trades = mergeSameKey(records);

        // 같은 단지가 거래 수만큼 반복되므로 aptSeq 기준으로 하나만 남긴다
        Map<String, TradeRecord> complexes = new LinkedHashMap<>();
        trades.forEach(r -> complexes.put(r.aptSeq(), r));

        LocalDate from = trades.stream().map(TradeRecord::dealDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = trades.stream().map(TradeRecord::dealDate).max(Comparator.naturalOrder()).orElseThrow();

        // batch 결과로는 INSERT와 UPDATE를 구분할 수 없어, 전후 행 수 차이로 신규 건수를 구한다
        long before = upsertRepository.countTrades(complexes.keySet(), from, to);
        upsertRepository.upsertComplexes(complexes.values());
        int affected = upsertRepository.upsertTrades(trades);
        long after = upsertRepository.countTrades(complexes.keySet(), from, to);

        int inserted = (int) (after - before);
        int updated = affected - inserted;
        int merged = records.size() - trades.size();
        return new UpsertResult(complexes.size(), inserted, updated, trades.size() - affected, merged);
    }

    /**
     * uk_apt_trade 키가 같은 레코드를 하나로 합친다.
     * 합치지 않으면 한 batch 안에서 서로 덮어써서, 수집할 때마다 응답 순서에 따라 값이 바뀐다.
     * 현재 키로는 구분할 수 없는 서로 다른 거래도 합쳐지는 한계가 있다 (ADR 002 한계 항목).
     */
    private static List<TradeRecord> mergeSameKey(List<TradeRecord> records) {
        Map<TradeKey, List<TradeRecord>> groups = new LinkedHashMap<>();
        records.forEach(r -> groups.computeIfAbsent(TradeKey.of(r), k -> new ArrayList<>()).add(r));

        List<TradeRecord> merged = new ArrayList<>(groups.size());
        groups.forEach((key, group) -> {
            if (group.size() > 1) {
                log.warn("같은 키의 거래 {}건을 1건으로 합침: {} (해제 {}건)", group.size(), key,
                        group.stream().filter(TradeRecord::cancelled).count());
            }
            merged.add(group.stream().min(MERGE_PRIORITY).orElseThrow());
        });
        return merged;
    }

    /**
     * uk_apt_trade와 같은 구성. 면적은 scale이 다르면 equals가 달라지므로 소수 둘째 자리로 맞춘다.
     */
    private record TradeKey(String aptSeq, LocalDate dealDate, BigDecimal excluUseAr, short floor, int dealAmount) {

        static TradeKey of(TradeRecord r) {
            return new TradeKey(r.aptSeq(), r.dealDate(), r.excluUseAr().setScale(2, RoundingMode.HALF_UP),
                    r.floor(), r.dealAmount());
        }
    }
}
