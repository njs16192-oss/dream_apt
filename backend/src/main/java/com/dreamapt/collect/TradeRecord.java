package com.dreamapt.collect;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * API 응답의 거래 1건. 단지 정보와 거래 정보가 함께 들어 있다.
 * 값이 없는 항목은 null (API의 공백 문자열을 정리한 결과).
 */
public record TradeRecord(
        // 단지
        String aptSeq,
        String aptNm,
        String sggCd,
        String umdCd,
        String umdNm,
        String jibun,
        String roadAddress,
        Short buildYear,
        // 거래
        LocalDate dealDate,
        int dealAmount, // 단위: 만원
        BigDecimal excluUseAr, // 전용면적(㎡), 소수 둘째 자리
        short floor,
        String aptDong,
        String buyerGbn,
        String sellerGbn,
        String dealingGbn,
        String agentSggNm,
        boolean cancelled,
        LocalDate cancelDate,
        LocalDate registeredDate) {
}
