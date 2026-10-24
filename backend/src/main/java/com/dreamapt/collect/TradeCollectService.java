package com.dreamapt.collect;

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
    private final TradeReconcileService reconcileService;

    public CollectResult collect(String sggCd, String dealYm) {
        FetchedTrades fetched = fetchComplete(sggCd, dealYm);
        ReconcileResult result = reconcileService.save(sggCd, dealYm, fetched.records());
        log.info("수집 완료 sggCd={} dealYm={} fetched={} {}", sggCd, dealYm, fetched.records().size(), result);
        return CollectResult.of(sggCd, dealYm, fetched.records().size(), result);
    }

    /**
     * 받은 건수가 totalCount와 다르면 처음부터 한 번 다시 받는다. 페이지를 받는 사이에 새 신고가 들어오면
     * totalCount가 바뀔 수 있기 때문이다. 그래도 다르면 대조하지 않고 실패한다 (ADR 004 3-⑤).
     */
    private FetchedTrades fetchComplete(String sggCd, String dealYm) {
        FetchedTrades fetched = apiClient.fetch(sggCd, dealYm);
        if (fetched.complete()) {
            return fetched;
        }
        log.warn("받은 건수가 totalCount와 달라 다시 받음 sggCd={} dealYm={} totalCount={} received={}",
                sggCd, dealYm, fetched.totalCount(), fetched.records().size());

        fetched = apiClient.fetch(sggCd, dealYm);
        if (!fetched.complete()) {
            throw new TradeApiException("원본 불완전: sggCd=" + sggCd + ", dealYm=" + dealYm
                    + ", totalCount=" + fetched.totalCount() + ", 받은 건수=" + fetched.records().size());
        }
        return fetched;
    }
}
