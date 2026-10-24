package com.dreamapt.collect;

import java.util.List;

/**
 * 수집 1회 결과. fetched(API에서 받은 거래 수) = inserted + updated + unchanged.
 * missing은 응답에 없는 DB 행 수이고, warnings에 경고 코드가 들어간다.
 */
public record CollectResult(
        String sggCd,
        String dealYm,
        int fetched,
        int complexes,
        int inserted,
        int updated,
        int unchanged,
        int missing,
        List<String> warnings) {

    public static CollectResult of(String sggCd, String dealYm, int fetched, ReconcileResult result) {
        return new CollectResult(sggCd, dealYm, fetched, result.complexes(),
                result.inserted(), result.updated(), result.unchanged(), result.missing(), result.warnings());
    }
}
