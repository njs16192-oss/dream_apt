package com.dreamapt.common;

/** 파라미터 형식은 맞지만 값의 조합이 잘못됐을 때 (예: from이 to보다 늦음). 컨트롤러에서 400으로 응답한다. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
