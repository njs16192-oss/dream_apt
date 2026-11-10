package com.dreamapt.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dreamapt.common.ApiExceptionHandler;
import com.dreamapt.common.ConflictException;
import com.dreamapt.common.NotFoundException;
import com.dreamapt.member.LoginMember;
import com.dreamapt.member.MemberRole;
import com.dreamapt.security.SecurityConfig;

/** 실제 SQL·제한 규칙은 통합 테스트에서 본다. 여기서는 인증·CSRF와 응답 모양만 본다 */
@WebMvcTest({ WatchlistController.class, ApiExceptionHandler.class })
@Import(SecurityConfig.class)
class WatchlistControllerTest {

    private static final long MEMBER_ID = 7L;
    private static final String HELIO = "11710-8865";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    WatchlistService service;

    @Test
    void 목록은_로그인한_회원의_것을_준다() throws Exception {
        given(service.findAll(MEMBER_ID)).willReturn(List.of(
                new WatchlistItem(HELIO, "헬리오시티", "11710", "송파구", LocalDate.of(2026, 10, 12),
                        OffsetDateTime.parse("2026-11-01T01:00:00Z")),
                new WatchlistItem("11710-1", "파크리오", "11710", "송파구", null,
                        OffsetDateTime.parse("2026-10-31T01:00:00Z"))));

        String body = mockMvc.perform(get("/api/me/watchlist").with(loggedIn()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("""
                [{"aptSeq":"11710-8865","name":"헬리오시티","sggCd":"11710","sggName":"송파구",\
                "lastDealDate":"2026-10-12","addedAt":"2026-11-01T01:00:00Z"},\
                {"aptSeq":"11710-1","name":"파크리오","sggCd":"11710","sggName":"송파구",\
                "lastDealDate":null,"addedAt":"2026-10-31T01:00:00Z"}]""");
    }

    @Test
    void 추가하면_204이고_세션의_회원_id로_추가한다() throws Exception {
        mockMvc.perform(put("/api/me/watchlist/{aptSeq}", HELIO).with(loggedIn()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).add(MEMBER_ID, HELIO);
    }

    @Test
    void 없는_단지를_추가하면_ProblemDetail_404() throws Exception {
        willThrow(new NotFoundException("단지를 찾을 수 없습니다")).given(service).add(MEMBER_ID, "99999-1");

        mockMvc.perform(put("/api/me/watchlist/{aptSeq}", "99999-1").with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("단지를 찾을 수 없습니다"))
                .andExpect(jsonPath("$.instance").value("/api/me/watchlist/99999-1"));
    }

    @Test
    void 개수_제한을_넘으면_ProblemDetail_409() throws Exception {
        willThrow(new ConflictException("관심단지는 최대 50개까지입니다")).given(service).add(MEMBER_ID, HELIO);

        mockMvc.perform(put("/api/me/watchlist/{aptSeq}", HELIO).with(loggedIn()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("관심단지는 최대 50개까지입니다"));
    }

    @Test
    void 해제하면_204이고_세션의_회원_id로_해제한다() throws Exception {
        mockMvc.perform(delete("/api/me/watchlist/{aptSeq}", HELIO).with(loggedIn()).with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).remove(MEMBER_ID, HELIO);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "GET", "PUT", "DELETE" })
    void 로그인하지_않으면_ProblemDetail_401이고_서비스를_부르지_않는다(String method) throws Exception {
        mockMvc.perform(request(method).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "PUT", "DELETE" })
    void CSRF_토큰이_없으면_ProblemDetail_403이고_서비스를_부르지_않는다(String method) throws Exception {
        mockMvc.perform(request(method).with(loggedIn()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    private static MockHttpServletRequestBuilder request(String method) {
        String path = method.equals("GET") ? "/api/me/watchlist" : "/api/me/watchlist/" + HELIO;
        return MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path);
    }

    private static RequestPostProcessor loggedIn() {
        LoginMember member = new LoginMember(MEMBER_ID, "me@example.com", MemberRole.USER, null);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(member, null, member.getAuthorities()));
    }
}
