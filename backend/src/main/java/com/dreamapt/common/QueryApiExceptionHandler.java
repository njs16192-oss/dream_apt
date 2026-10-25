package com.dreamapt.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.dreamapt.complex.ComplexController;
import com.dreamapt.region.RegionController;

/**
 * 조회 API 오류를 ProblemDetail로 응답한다. 파라미터 검증·형식 오류(400)는 ResponseEntityExceptionHandler가 처리한다.
 * 관리 API의 기존 오류 응답 형식을 바꾸지 않도록 조회 컨트롤러에만 적용한다 (ApiJsonCompatibilityTest).
 */
@RestControllerAdvice(assignableTypes = { ComplexController.class, RegionController.class })
public class QueryApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    ProblemDetail handleBadRequest(BadRequestException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
