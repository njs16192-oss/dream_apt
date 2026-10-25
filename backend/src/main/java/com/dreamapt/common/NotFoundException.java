package com.dreamapt.common;

/** 조회 대상(단지, 면적 그룹, 구)이 없을 때. 컨트롤러에서 404로 응답한다. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
