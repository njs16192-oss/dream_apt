package com.dreamapt.collect;

/**
 * 수집 1회 결과. fetched는 API에서 받은 거래 수.
 */
public record CollectResult(
        String sggCd,
        String dealYm,
        int fetched,
        int complexes,
        int inserted,
        int updated,
        int unchanged) {

    public static CollectResult of(String sggCd, String dealYm, int fetched, UpsertResult upsert) {
        return new CollectResult(sggCd, dealYm, fetched,
                upsert.complexes(), upsert.inserted(), upsert.updated(), upsert.unchanged());
    }
}
