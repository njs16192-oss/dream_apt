package com.dreamapt.collect;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 한 시군구·한 달 치 거래를 API에서 받아 저장한다.
 * API 호출은 트랜잭션 밖에서 하고(DB 커넥션을 오래 잡지 않도록), 저장만 트랜잭션으로 묶는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeCollectService {

    private final TradeApiClient apiClient;
    private final TradeUpsertService upsertService;

    public CollectResult collect(String sggCd, String dealYm) {
        List<TradeRecord> records = apiClient.fetch(sggCd, dealYm);
        UpsertResult result = upsertService.save(records);
        log.info("수집 완료 sggCd={} dealYm={} fetched={} {}", sggCd, dealYm, records.size(), result);
        return CollectResult.of(sggCd, dealYm, records.size(), result);
    }
}
