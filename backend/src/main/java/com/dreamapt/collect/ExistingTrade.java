package com.dreamapt.collect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 대조 대상인 DB의 거래 행. 수집한 구×월 범위의 행을 missing 행까지 포함해서 읽는다.
 */
public record ExistingTrade(
        long id,
        String aptSeq,
        LocalDate dealDate,
        int dealAmount, // 단위: 만원
        BigDecimal excluUseAr,
        short floor,
        String aptDong,
        String buyerGbn,
        String sellerGbn,
        String dealingGbn,
        String agentSggNm,
        boolean cancelled,
        LocalDate cancelDate,
        LocalDate registeredDate,
        OffsetDateTime missingSince) {

    public boolean missing() {
        return missingSince != null;
    }
}
