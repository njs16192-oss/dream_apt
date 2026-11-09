package com.dreamapt.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.dreamapt.support.TestcontainersConfiguration;

import jakarta.servlet.http.Cookie;

/**
 * 가입 → 로그인 → 내 정보 → 로그아웃을 실제 DB·필터 체인으로 돌린다.
 * CSRF는 테스트용 csrf() 대신 브라우저처럼 XSRF-TOKEN 쿠키를 받아 X-XSRF-TOKEN 헤더로 되돌려 보낸다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class LoginIntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired
    MockMvc mockMvc;

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
        MockHttpSession session = new MockHttpSession();

        MvcResult login = mockMvc.perform(login(session, email.toUpperCase(), PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn();

        mockMvc.perform(get("/api/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"));
        // 로그인 응답과 내 정보가 같다
        assertThat(login.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(mockMvc.perform(get("/api/me").session(session)).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 로그인하면_세션_ID와_CSRF_토큰이_바뀐다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        MockHttpServletResponse response = mockMvc.perform(login(session, email, PASSWORD))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(session.getId()).isNotEqualTo(before);
        // 옛 쿠키를 지우는 Set-Cookie(빈 값) 뒤에 새 토큰이 온다. 브라우저에는 마지막 값이 남는다
        assertThat(xsrfCookies(response)).first().extracting(Cookie::getValue).isEqualTo("");
        Cookie renewed = lastXsrfCookie(response);
        assertThat(renewed.getValue()).isNotBlank().isNotEqualTo(xsrf.getValue());
    }

    @Test
    void 세션에는_비밀번호_해시가_남지_않는다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(login(session, email, PASSWORD)).andExpect(status().isOk());

        SecurityContext context = (SecurityContext) session
                .getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        LoginMember principal = (LoginMember) context.getAuthentication().getPrincipal();
        assertThat(principal.getEmail()).isEqualTo(email);
        assertThat(principal.getPassword()).isNull();
        assertThat(context.getAuthentication().getCredentials()).isNull();
    }

    @Test
    void 없는_이메일과_틀린_비밀번호는_같은_401_응답이다() throws Exception {
        MockHttpServletResponse wrongPassword = mockMvc
                .perform(login(new MockHttpSession(), email, "wrong-password"))
                .andReturn().getResponse();
        MockHttpServletResponse unknownEmail = mockMvc
                .perform(login(new MockHttpSession(), "nobody-" + UUID.randomUUID() + "@example.com", PASSWORD))
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
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(login(session, email, "wrong-password")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃하면_세션이_끝난다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // 로그인 직후 다른 GET 없이 바로 POST해도 된다 (로그인 응답의 새 토큰을 쓴다)
        Cookie afterLogin = lastXsrfCookie(mockMvc.perform(login(session, email, PASSWORD)).andReturn().getResponse());

        mockMvc.perform(withXsrf(post("/api/auth/logout"), afterLogin).session(session))
                .andExpect(status().isNoContent());

        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
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

    private MockHttpServletRequestBuilder login(MockHttpSession session, String email, String password) {
        return withXsrf(post("/api/auth/login"), xsrf).session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(email, password));
    }

    @Test
    void 로그인_전_CSRF_토큰은_로그인_뒤에_쓸_수_없다() throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie afterLogin = lastXsrfCookie(mockMvc.perform(login(session, email, PASSWORD)).andReturn().getResponse());

        // 브라우저에는 새 쿠키만 남는다. 옛 토큰을 아는 쪽(로그인 전에 심어 둔 공격자 등)이 헤더에 넣으면 맞지 않는다
        mockMvc.perform(post("/api/auth/logout").session(session)
                .cookie(afterLogin)
                .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isForbidden());
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
