package com.dreamapt.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.dreamapt.common.ApiExceptionHandler;
import com.dreamapt.common.ConflictException;

@WebMvcTest({ AuthController.class, ApiExceptionHandler.class })
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    SignupService signupService;

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

    private ResultActions signup(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }
}
