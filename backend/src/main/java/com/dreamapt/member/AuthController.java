package com.dreamapt.member;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@Tag(name = "회원", description = "회원 가입·로그인. 로그인은 세션 쿠키로 유지된다. "
        + "GET 이외 요청은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내야 한다")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 없는 이메일과 틀린 비밀번호를 구분하지 않는다. 구분하면 가입 여부가 드러난다 (ADR 011 ①) */
    static final String LOGIN_FAILED = "이메일 또는 비밀번호가 맞지 않습니다";

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    private final SignupService signupService;
    private final AuthenticationManager authenticationManager;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;

    @Operation(summary = "회원 가입", description = "이메일은 소문자로 저장한다. 이미 가입된 이메일이면 409. 가입만 하고 로그인하지는 않는다")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        Member member = signupService.signup(request.email(), request.password());
        return new SignupResponse(member.getId(), member.getEmail());
    }

    @Operation(summary = "로그인", description = "성공하면 세션 ID와 CSRF 토큰을 새로 발급한다. 실패하면 401 (이유를 나누지 않는다)")
    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));

        // formLogin이면 필터가 해 주는 일을 직접 한다: 세션 ID·CSRF 토큰 교체 → SecurityContext를 세션에 저장
        sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        contextHolder.setContext(context);
        // Spring Security 6부터 SecurityContext는 자동으로 세션에 저장되지 않는다. 빠뜨리면 다음 요청에서 로그아웃 상태다
        securityContextRepository.saveContext(context, httpRequest, httpResponse);

        return MeResponse.from((LoginMember) authentication.getPrincipal());
    }

    @Operation(summary = "로그아웃", description = "세션을 끝내고 CSRF 토큰을 지운다. 로그인하지 않았어도 204")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        Authentication authentication = contextHolder.getContext().getAuthentication();
        // 세션 무효화 + SecurityContext 비우기
        new SecurityContextLogoutHandler().logout(httpRequest, httpResponse, authentication);
        // 쓰던 CSRF 토큰 쿠키를 지운다. 다음 요청에서 새 토큰이 내려간다
        new CsrfLogoutHandler(csrfTokenRepository).logout(httpRequest, httpResponse, authentication);
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleLoginFailed(AuthenticationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, LOGIN_FAILED);
    }
}
