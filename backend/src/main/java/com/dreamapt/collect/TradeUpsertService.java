package com.dreamapt.collect;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 수집한 거래를 DB에 저장한다.
 * {@link TradeCollectService}와 분리한 이유: 같은 클래스 안에서 호출하면 프록시를 거치지 않아
 * {@code @Transactional}이 적용되지 않는다.
 */
@Service
@RequiredArgsConstructor
public class TradeUpsertService {

    private final TradeUpsertRepository upsertRepository;

    /**
     * 단지를 먼저(FK), 거래를 나중에 upsert 한다. 하나라도 실패하면 전체 롤백.
     */
    @Transactional
    public UpsertResult save(List<TradeRecord> records) {
        if (records.isEmpty()) {
            return UpsertResult.EMPTY;
        }

        // 같은 단지가 거래 수만큼 반복되므로 aptSeq 기준으로 하나만 남긴다
        Map<String, TradeRecord> complexes = new LinkedHashMap<>();
        records.forEach(r -> complexes.put(r.aptSeq(), r));

        LocalDate from = records.stream().map(TradeRecord::dealDate).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate to = records.stream().map(TradeRecord::dealDate).max(Comparator.naturalOrder()).orElseThrow();

        // batch 결과로는 INSERT와 UPDATE를 구분할 수 없어, 전후 행 수 차이로 신규 건수를 구한다
        long before = upsertRepository.countTrades(complexes.keySet(), from, to);
        upsertRepository.upsertComplexes(complexes.values());
        int affected = upsertRepository.upsertTrades(records);
        long after = upsertRepository.countTrades(complexes.keySet(), from, to);

        int inserted = (int) (after - before);
        int updated = affected - inserted;
        return new UpsertResult(complexes.size(), inserted, updated, records.size() - affected);
    }
}
