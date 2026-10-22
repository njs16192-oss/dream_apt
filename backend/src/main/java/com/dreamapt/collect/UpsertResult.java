package com.dreamapt.collect;

/**
 * 거래 upsert 결과 건수.
 *
 * @param complexes 저장 대상 단지 수 (중복 제거 후)
 * @param inserted  새로 추가된 거래
 * @param updated   이미 있었고 값이 바뀐 거래
 * @param unchanged 이미 있었고 값도 같은 거래
 */
public record UpsertResult(int complexes, int inserted, int updated, int unchanged) {

    public static final UpsertResult EMPTY = new UpsertResult(0, 0, 0, 0);
}
