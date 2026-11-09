package com.dreamapt.security;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 필터 체인에서 난 401·403을 ProblemDetail로 쓴다. 컨트롤러 밖이라 ApiExceptionHandler가 받지 못한다.
 * <ul>
 * <li>401 (인증 필요): 로그인하지 않았다. "누구인지 모른다"</li>
 * <li>403 (권한 없음): 누구인지는 알지만 허용되지 않았다. CSRF 토큰이 없거나 틀린 경우도 403이다</li>
 * </ul>
 */
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    static final String UNAUTHORIZED = "로그인이 필요합니다";
    static final String FORBIDDEN = "권한이 없습니다";
    static final String CSRF = "CSRF 토큰이 없거나 맞지 않습니다. XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내야 합니다";

    /** 컨트롤러 응답과 같은 Jackson 설정 + ProblemDetail 형식(mixin)으로 쓴다 */
    private final JacksonJsonHttpMessageConverter converter;

    public SecurityProblemHandler(JsonMapper jsonMapper) {
        this.converter = new JacksonJsonHttpMessageConverter(jsonMapper.rebuild());
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        String detail = accessDeniedException instanceof CsrfException ? CSRF : FORBIDDEN;
        write(request, response, HttpStatus.FORBIDDEN, detail);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        // 컨트롤러의 ProblemDetail처럼 요청 경로를 instance에 넣는다
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        converter.write(problem, MediaType.APPLICATION_PROBLEM_JSON, new ServletServerHttpResponse(response));
    }
}
