package com.dreamapt.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.dreamapt.support.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;

/**
 * 가입 → 로그인 → 내 정보 → 로그아웃을 실제 DB·필터 체인으로 돌린다.
 * CSRF는 테스트용 csrf() 대신 브라우저처럼 XSRF-TOKEN 쿠키를 받아 X-XSRF-TOKEN 헤더로 되돌려 보낸다.
 * 세션도 Spring Session JDBC가 DB에 두므로 MockHttpSession 대신 브라우저처럼 SESSION 쿠키를 받아 다음 요청에 싣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginIntegrationTest {

    private static final String PASSWORD = "password123";
    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    SessionRepository<? extends Session> sessionRepository;

    String email;
    Cookie xsrf;

    @BeforeEach
    void 가입한다() throws Exception {
        email = "user-" + UUID.randomUUID() + "@example.com";
        xsrf = mockMvc.perform(get("/api/ping")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        mockMvc.perform(withXsrf(post("/api/auth/signup"), xsrf).contentType(MediaType.APPLICATION_JSON)
                .content(json(email, PASSWORD)))
                .andExpect(status().isCreated());
    }

    @Test
    void 아무_GET_응답에나_XSRF_TOKEN_쿠키가_JS가_읽을_수_있게_실린다() {
        assertThat(xsrf).isNotNull();
        assertThat(xsrf.getValue()).isNotBlank();
        assertThat(xsrf.isHttpOnly()).isFalse();
    }

    @Test
    void 로그인하면_세션에_남고_내_정보를_볼_수_있다() throws Exception {
        MvcResult login = mockMvc.perform(login(email.toUpperCase(), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn();
        Cookie session = sessionCookie(login.getResponse());

        mockMvc.perform(get("/api/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
        // 로그인 응답과 내 정보가 같다
        assertThat(login.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(mockMvc.perform(get("/api/me").cookie(session)).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 로그인하면_세션_ID와_CSRF_토큰이_바뀐다() throws Exception {
        // 로그인 전에 세션이 있어야 ID를 바꾼다. 로그인 전에 받은(또는 공격자가 심어 둔) 세션을 DB에 만들어 둔다
        String before = createSession();

        MockHttpServletResponse response = mockMvc.perform(login(cookieOf(before), email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        String after = sessionIdOf(sessionCookie(response));
        assertThat(after).isNotEqualTo(before);
        // 옛 ID로는 더 이상 세션을 찾을 수 없다
        assertThat(sessionRepository.findById(before)).isNull();
        assertThat(sessionRepository.findById(after)).isNotNull();
        // 옛 쿠키를 지우는 Set-Cookie(빈 값) 뒤에 새 토큰이 온다. 브라우저에는 마지막 값이 남는다
        assertThat(xsrfCookies(response)).first().extracting(Cookie::getValue).isEqualTo("");
        Cookie renewed = lastXsrfCookie(response);
        assertThat(renewed.getValue()).isNotBlank().isNotEqualTo(xsrf.getValue());
    }

    @Test
    void 세션에는_비밀번호_해시가_남지_않는다() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(login(email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        // DB에 직렬화돼 저장된 세션을 다시 읽어 확인한다
        Session session = sessionRepository.findById(sessionIdOf(sessionCookie(response)));
        assertThat(session).isNotNull();
        SecurityContext context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        LoginMember principal = (LoginMember) context.getAuthentication().getPrincipal();
        assertThat(principal.getEmail()).isEqualTo(email);
        assertThat(principal.getPassword()).isNull();
        assertThat(context.getAuthentication().getCredentials()).isNull();
    }

    @Test
    void 없는_이메일과_틀린_비밀번호는_같은_401_응답이다() throws Exception {
        MockHttpServletResponse wrongPassword = mockMvc
                .perform(login(email, "wrong-password"))
                .andReturn().getResponse();
        MockHttpServletResponse unknownEmail = mockMvc
                .perform(login("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD))
                .andReturn().getResponse();

        assertThat(wrongPassword.getStatus()).isEqualTo(401);
        assertThat(wrongPassword.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(wrongPassword.getContentAsString(StandardCharsets.UTF_8))
                .contains(AuthController.LOGIN_FAILED)
                .isEqualTo(unknownEmail.getContentAsString(StandardCharsets.UTF_8));
        assertThat(unknownEmail.getStatus()).isEqualTo(401);
    }

    @Test
    void 로그인에_실패하면_세션에_로그인이_남지_않는다() throws Exception {
        Cookie session = cookieOf(createSession());
        mockMvc.perform(login(session, email, "wrong-password")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_세션이_끝난다() throws Exception {
        MockHttpServletResponse login = mockMvc.perform(login(email, PASSWORD)).andReturn().getResponse();
        Cookie session = sessionCookie(login);
        // 로그인 직후 다른 GET 없이 바로 POST해도 된다 (로그인 응답의 새 토큰을 쓴다)
        Cookie afterLogin = lastXsrfCookie(login);

        mockMvc.perform(withXsrf(post("/api/auth/logout"), afterLogin).cookie(session))
                .andExpect(status().isNoContent());

        assertThat(sessionRepository.findById(sessionIdOf(session))).isNull();
        // 쿠키를 그대로 들고 있어도(지우는 응답을 무시한 클라이언트 등) 로그인 상태가 아니다
        mockMvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void 로그인하지_않고_로그아웃해도_204() throws Exception {
        mockMvc.perform(withXsrf(post("/api/auth/logout"), xsrf)).andExpect(status().isNoContent());
    }

    @Test
    void 헤더의_CSRF_토큰이_쿠키와_다르면_403() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                .cookie(xsrf)
                .header("X-XSRF-TOKEN", "forged-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(email, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("CSRF 토큰")));
    }

    @Test
    void CSRF_헤더가_없으면_쿠키만으로는_403() throws Exception {
        // 다른 사이트의 폼 전송처럼 쿠키는 자동으로 실려도 헤더는 만들 수 없는 경우
        mockMvc.perform(post("/api/auth/login")
                .cookie(xsrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(email, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private MockHttpServletRequestBuilder login(String email, String password) {
        return withXsrf(post("/api/auth/login"), xsrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(email, password));
    }

    private MockHttpServletRequestBuilder login(Cookie session, String email, String password) {
        return login(email, password).cookie(session);
    }

    @Test
    void 로그인_전_CSRF_토큰은_로그인_뒤에_쓸_수_없다() throws Exception {
        MockHttpServletResponse login = mockMvc.perform(login(email, PASSWORD)).andReturn().getResponse();
        Cookie afterLogin = lastXsrfCookie(login);

        // 브라우저에는 새 쿠키만 남는다. 옛 토큰을 아는 쪽(로그인 전에 심어 둔 공격자 등)이 헤더에 넣으면 맞지 않는다
        mockMvc.perform(post("/api/auth/logout")
                .cookie(sessionCookie(login), afterLogin)
                .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isForbidden());
    }

    /** 세션을 DB에 만들고 ID를 돌려준다 */
    private String createSession() {
        return save(sessionRepository);
    }

    private static <S extends Session> String save(SessionRepository<S> repository) {
        S session = repository.createSession();
        repository.save(session);
        return session.getId();
    }

    /** SESSION 쿠키 값은 세션 ID를 Base64로 인코딩한 것이다 (DefaultCookieSerializer 기본값) */
    private static Cookie cookieOf(String sessionId) {
        return new Cookie(SESSION_COOKIE,
                Base64.getEncoder().encodeToString(sessionId.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sessionIdOf(Cookie sessionCookie) {
        return new String(Base64.getDecoder().decode(sessionCookie.getValue()), StandardCharsets.UTF_8);
    }

    private static Cookie sessionCookie(MockHttpServletResponse response) {
        Cookie cookie = response.getCookie(SESSION_COOKIE);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        return cookie;
    }

    private static List<Cookie> xsrfCookies(MockHttpServletResponse response) {
        return Arrays.stream(response.getCookies()).filter(c -> c.getName().equals("XSRF-TOKEN")).toList();
    }

    private static Cookie lastXsrfCookie(MockHttpServletResponse response) {
        List<Cookie> cookies = xsrfCookies(response);
        assertThat(cookies).isNotEmpty();
        return cookies.get(cookies.size() - 1);
    }

    private static MockHttpServletRequestBuilder withXsrf(MockHttpServletRequestBuilder request, Cookie token) {
        return request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
    }

    private static String json(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
