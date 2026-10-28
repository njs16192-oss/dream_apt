package com.dreamapt.geocode;

/**
 * 도로명주소 API 호출·응답 처리 실패. 종류에 따라 지오코딩이 그 단지만 넘길지, 실행을 멈출지 정한다 (ADR 010).
 */
public class JusoApiException extends RuntimeException {

    public enum Kind {
        /** 타임아웃, 연결 실패, 5xx, 시스템 오류(-999). 기록하지 않고 다음 실행에서 다시 시도한다 */
        TRANSIENT,
        /** 승인키 없음·미승인·만료. 남은 호출도 모두 실패하므로 실행을 멈춘다 */
        AUTH,
        /** 그 밖의 실패 (검색어 형식 오류, 파싱 오류 등). 그 단지만 API_ERROR로 기록한다 */
        OTHER
    }

    private final Kind kind;

    public JusoApiException(Kind kind, String message) {
        this(kind, message, null);
    }

    public JusoApiException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
