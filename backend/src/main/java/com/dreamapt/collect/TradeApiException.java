package com.dreamapt.collect;

/**
 * 실거래가 API 호출·응답 처리 실패.
 */
public class TradeApiException extends RuntimeException {

    public TradeApiException(String message) {
        super(message);
    }

    public TradeApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
