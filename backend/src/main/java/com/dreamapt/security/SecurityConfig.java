package com.dreamapt.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;

import tools.jackson.databind.json.JsonMapper;

/**
 * 인증·인가 규칙 (ADR 011 ①, 경로 규칙). 필터 체인은 DispatcherServlet 앞에서 돈다.
 * <ul>
 * <li>로그인은 formLogin 대신 JSON API(AuthController)로 한다. 그래야 API 문서와 schema.d.ts에 들어간다</li>
 * <li>세션 쿠키 + CSRF(쿠키 XSRF-TOKEN, 헤더 X-XSRF-TOKEN). 쿠키 설정은 application.yml의 server.servlet.session.cookie</li>
 * <li>401·403은 필터에서 나므로 ApiExceptionHandler가 아니라 SecurityProblemHandler가 ProblemDetail로 쓴다</li>
 * </ul>
 * DB 쪽 빈(MemberRepository 등)에 기대지 않는다. 로그인 인증(UserDetailsService, AuthenticationManager)은
 * member.LoginConfig에 있다. 그래서 @WebMvcTest 슬라이스도 @Import(SecurityConfig.class)로 같은 규칙을 쓸 수 있다.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityProblemHandler problemHandler,
            SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // 위에서부터 처음 맞는 규칙 하나만 적용된다
                        .requestMatchers(HttpMethod.GET, "/api/ping", "/api/complexes/**", "/api/regions/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/logout")
                        .permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        // 컨트롤러 밖 오류(Tomcat 400 등)는 /error로 다시 들어온다. 막으면 원래 상태 코드 대신 401이 나간다
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/me", "/api/me/**").authenticated()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // 규칙에 없는 경로는 거부한다. 새 API를 만들고 규칙을 빼먹으면 열리는 대신 막힌다
                        .anyRequest().denyAll())
                // spa(): XSRF-TOKEN 쿠키(JS가 읽도록 HttpOnly 아님)를 요청마다 내려주고 X-XSRF-TOKEN 헤더 값과 비교한다
                .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemHandler)
                        .accessDeniedHandler(problemHandler))
                // 401 때 원래 요청을 세션에 저장해 두는 기능(로그인 후 되돌아가기용). SPA에는 필요 없고 익명 세션만 만든다
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // 로그아웃은 AuthController가 한다 (API 문서에 들어가게)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    /** @Component 대신 여기서 만든다. @WebMvcTest가 @Import(SecurityConfig.class) 하나로 401·403 처리까지 가져가게 */
    @Bean
    SecurityProblemHandler securityProblemHandler(JsonMapper jsonMapper) {
        return new SecurityProblemHandler(jsonMapper);
    }

    /** 로그인한 SecurityContext를 세션에 둔다. 필터 체인과 AuthController가 같은 객체를 써야 한다 */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** csrf.spa()가 쓰는 것과 같은 설정. 로그인 때 토큰을 새로 만들려고 빈으로 꺼내 둔다 */
    @Bean
    CsrfTokenRepository csrfTokenRepository() {
        return CookieCsrfTokenRepository.withHttpOnlyFalse();
    }

    /**
     * 로그인 성공 직후 실행한다. formLogin이면 필터가 해 주지만 JSON 로그인은 직접 불러야 한다.
     * <ul>
     * <li>세션 ID 변경: 로그인 전에 공격자가 심어 둔 세션 ID가 로그인 후에도 쓰이는 것(세션 고정)을 막는다</li>
     * <li>CSRF 토큰 교체: 로그인 전 토큰을 로그인 후에 쓰지 못하게 한다</li>
     * </ul>
     */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository) {
        CsrfAuthenticationStrategy csrfStrategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
        // 기본값은 옛 토큰 쿠키를 지우기만 하고 새 토큰은 다음 요청에서 내려준다. 그러면 SPA가 로그인 직후 GET 없이
        // POST(로그아웃 등)를 보내면 403이다. 속성 이름을 null로 두면 새 토큰을 바로 꺼내 로그인 응답에 쿠키로 싣는다 (spa()와 같은 방식)
        CsrfTokenRequestAttributeHandler eagerToken = new CsrfTokenRequestAttributeHandler();
        eagerToken.setCsrfRequestAttributeName(null);
        csrfStrategy.setRequestHandler(eagerToken);
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                csrfStrategy));
    }
}
