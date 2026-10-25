package com.dreamapt.collect;

/**
 * 실거래가 API 호출·응답 처리 실패. 종류에 따라 배치가 재시도·중단 여부를 정한다 (ADR 006 3-②).
 */
public class TradeApiException extends RuntimeException {

    public enum Kind {
        /** 타임아웃, 연결 실패, 5xx 등. 잠시 뒤 다시 하면 성공할 수 있다 */
        TRANSIENT,
        /** 일일 트래픽 한도 초과. 남은 호출도 모두 실패하므로 실행을 멈춘다 */
        QUOTA_EXCEEDED,
        /** 인증 실패 (키 없음·미등록·만료). 실행을 멈춘다 */
        AUTH,
        /** 그 밖의 실패 (원본 불완전, 범위 밖 레코드, 파싱 오류 등). 그 구×월만 실패 */
        OTHER;

        /** 이 오류가 나면 남은 구×월을 처리하지 않고 실행을 멈추는가 */
        public boolean abortsRun() {
            return this == QUOTA_EXCEEDED || this == AUTH;
        }
    }

    private final Kind kind;

    public TradeApiException(String message) {
        this(Kind.OTHER, message, null);
    }

    public TradeApiException(String message, Throwable cause) {
        this(Kind.OTHER, message, cause);
    }

    public TradeApiException(Kind kind, String message) {
        this(kind, message, null);
    }

    public TradeApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
