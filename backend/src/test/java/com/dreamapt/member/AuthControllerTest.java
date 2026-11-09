package com.dreamapt.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.dreamapt.common.ApiExceptionHandler;
import com.dreamapt.common.ConflictException;
import com.dreamapt.security.SecurityConfig;

@WebMvcTest({ AuthController.class, ApiExceptionHandler.class })
@Import(SecurityConfig.class)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SignupService signupService;

    /** 실제 인증(DB 조회·해시 비교)은 LoginIntegrationTest에서 본다. 여기서는 컨트롤러가 결과를 어떻게 응답하는지만 본다 */
    @MockitoBean
    AuthenticationManager authenticationManager;

    @Test
    void 가입하면_201과_id_email만_준다() throws Exception {
        Member member = Member.create("a@b.com", "{bcrypt}$2a$10$hash");
        ReflectionTestUtils.setField(member, "id", 1L);
        given(signupService.signup("a@b.com", "password123")).willReturn(member);

        String body = signup("a@b.com", "password123")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("{\"id\":1,\"email\":\"a@b.com\"}");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "잘못된 이메일, not-an-email, password123",
            "비밀번호 7자, a@b.com, passwor",
            "비밀번호 73자, a@b.com, " + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    })
    void 검증에_실패하면_ProblemDetail_400이고_서비스를_부르지_않는다(String name, String email, String password)
            throws Exception {
        signup(email, password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.instance").value("/api/auth/signup"));
        verify(signupService, never()).signup(any(), any());
    }

    @Test
    void 중복_이메일은_ProblemDetail_409() throws Exception {
        given(signupService.signup("a@b.com", "password123")).willThrow(new ConflictException("이미 가입된 이메일입니다"));

        signup("a@b.com", "password123")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("이미 가입된 이메일입니다"));
    }

    @Test
    void CSRF_토큰_없이_가입하면_ProblemDetail_403이고_서비스를_부르지_않는다() throws Exception {
        mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.com\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("CSRF 토큰")))
                .andExpect(jsonPath("$.instance").value("/api/auth/signup"));
        verify(signupService, never()).signup(any(), any());
    }

    @Test
    void 로그인하면_내_정보를_준다() throws Exception {
        LoginMember member = new LoginMember(1L, "a@b.com", MemberRole.USER, null);
        given(authenticationManager.authenticate(any()))
                .willReturn(UsernamePasswordAuthenticationToken.authenticated(member, null, member.getAuthorities()));

        String body = login("A@B.com", "password123")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("{\"id\":1,\"email\":\"a@b.com\",\"role\":\"USER\"}");
    }

    @Test
    void 로그인에_실패하면_이유를_나누지_않고_ProblemDetail_401() throws Exception {
        given(authenticationManager.authenticate(any())).willThrow(new BadCredentialsException("Bad credentials"));

        login("a@b.com", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(AuthController.LOGIN_FAILED))
                .andExpect(jsonPath("$.instance").value("/api/auth/login"));
    }

    @Test
    void 로그인_요청에_빈_값이_있으면_400이고_인증을_시도하지_않는다() throws Exception {
        login("", "password123").andExpect(status().isBadRequest());
        verify(authenticationManager, never()).authenticate(any());
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions signup(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/signup").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }
}
