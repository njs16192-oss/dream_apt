package com.dreamapt.collect;

import java.util.List;

/**
 * 구×월 대조 결과 건수. inserted + updated + unchanged = 입력 레코드 수.
 *
 * @param complexes 저장 대상 단지 수 (중복 제거 후)
 * @param inserted  새로 추가된 거래
 * @param updated   이미 있었고 값이 바뀐 거래 (missing 표시 해제 포함)
 * @param unchanged 이미 있었고 값도 같은 거래
 * @param missing   응답에 짝이 없는 DB 행 수. 표시가 보류돼도 건수는 그대로 보고한다
 * @param warnings  경고 코드 (예: {@link #MISSING_RATIO_EXCEEDED})
 */
public record ReconcileResult(int complexes, int inserted, int updated, int unchanged, int missing,
        List<String> warnings) {

    /** 새 missing이 너무 많아 표시를 보류함 (ADR 004 3-⑤) */
    public static final String MISSING_RATIO_EXCEEDED = "MISSING_RATIO_EXCEEDED";

    public static final ReconcileResult EMPTY = new ReconcileResult(0, 0, 0, 0, 0, List.of());
}
