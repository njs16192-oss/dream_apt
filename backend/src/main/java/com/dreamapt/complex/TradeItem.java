package com.dreamapt.complex;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 거래 목록 한 건. 거래 목록은 일괄 거래도 포함하고 bulk로 표시한다 (ADR 007 ②).
 *
 * @param dealAmount     거래 금액(만원)
 * @param exclusiveArea  전용면적 원값(㎡)
 * @param areaGroup      전용면적 정수 반올림(㎡)
 * @param dealingGbn     거래 유형 (중개거래/직거래). 2021-11 이전 계약은 null
 * @param buyerGbn       매수자 구분 (개인/법인/공공기관/기타). 2024 이전 계약은 null
 * @param sellerGbn      매도자 구분. 2024 이전 계약은 null
 * @param registeredDate 등기일. 아직 등기되지 않았으면 null
 * @param bulk           일괄 거래 (같은 단지·같은 계약일 유효 거래 10건 이상)
 */
public record TradeItem(
        long id,
        LocalDate dealDate,
        int dealAmount,
        BigDecimal exclusiveArea,
        int areaGroup,
        int floor,
        String aptDong,
        String dealingGbn,
        String buyerGbn,
        String sellerGbn,
        LocalDate registeredDate,
        boolean bulk) {
}
