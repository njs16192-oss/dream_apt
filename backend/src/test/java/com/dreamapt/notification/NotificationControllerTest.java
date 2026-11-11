package com.dreamapt.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dreamapt.common.ApiExceptionHandler;
import com.dreamapt.common.NotFoundException;
import com.dreamapt.common.PageResponse;
import com.dreamapt.member.LoginMember;
import com.dreamapt.member.MemberRole;
import com.dreamapt.security.SecurityConfig;

/** 실제 SQL은 통합 테스트에서 본다. 여기서는 인증·CSRF·검증과 응답 모양만 본다 */
@WebMvcTest({ NotificationController.class, ApiExceptionHandler.class })
@Import(SecurityConfig.class)
class NotificationControllerTest {

    private static final long MEMBER_ID = 7L;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    NotificationService service;

    @Test
    void 목록은_로그인한_회원의_것을_페이지로_준다() throws Exception {
        NotificationItem item = new NotificationItem(31L, "11710-8865", "헬리오시티", 85, new BigDecimal("84.99"), 12,
                LocalDate.of(2026, 10, 12), 245_000, 238_000, OffsetDateTime.parse("2026-11-01T01:00:00Z"), false);
        given(service.findPage(MEMBER_ID, 1, 10)).willReturn(PageResponse.of(List.of(item), 1, 10, 11));

        String body = mockMvc.perform(get("/api/me/notifications").param("page", "1").param("size", "10").with(loggedIn()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEqualTo("""
                {"content":[{"id":31,"aptSeq":"11710-8865","complexName":"헬리오시티","areaGroup":85,\
                "exclusiveArea":84.99,"floor":12,"dealDate":"2026-10-12","dealAmount":245000,\
                "previousMaxAmount":238000,"createdAt":"2026-11-01T01:00:00Z","read":false}],\
                "page":1,"size":10,"totalElements":11,"totalPages":2}""");
    }

    @Test
    void 페이지_기본값은_0과_20이다() throws Exception {
        given(service.findPage(MEMBER_ID, 0, 20)).willReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/me/notifications").with(loggedIn()))
                .andExpect(status().isOk());

        verify(service).findPage(MEMBER_ID, 0, 20);
    }

    @ParameterizedTest(name = "page={0} size={1}")
    @CsvSource({ "-1, 20", "0, 0", "0, 101" })
    void 페이지_범위를_벗어나면_ProblemDetail_400(String page, String size) throws Exception {
        mockMvc.perform(get("/api/me/notifications").param("page", page).param("size", size).with(loggedIn()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void 안_읽음_수() throws Exception {
        given(service.countUnread(MEMBER_ID)).willReturn(3L);

        mockMvc.perform(get("/api/me/notifications/unread-count").with(loggedIn()))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"count\":3}", true));
    }

    @Test
    void 읽음_처리는_204이고_세션의_회원_id로_한다() throws Exception {
        mockMvc.perform(put("/api/me/notifications/{id}/read", 31).with(loggedIn()).with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(service).markRead(MEMBER_ID, 31L);
    }

    @Test
    void 내_알림이_아니면_ProblemDetail_404() throws Exception {
        willThrow(new NotFoundException("알림을 찾을 수 없습니다")).given(service).markRead(MEMBER_ID, 99L);

        mockMvc.perform(put("/api/me/notifications/{id}/read", 99).with(loggedIn()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("알림을 찾을 수 없습니다"));
    }

    @Test
    void 숫자가_아닌_id는_ProblemDetail_400() throws Exception {
        mockMvc.perform(put("/api/me/notifications/{id}/read", "abc").with(loggedIn()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    @Test
    void 모두_읽음은_204이고_세션의_회원_id로_한다() throws Exception {
        mockMvc.perform(put("/api/me/notifications/read-all").with(loggedIn()).with(csrf()))
                .andExpect(status().isNoContent());

        verify(service).markAllRead(MEMBER_ID);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET, /api/me/notifications",
            "GET, /api/me/notifications/unread-count",
            "PUT, /api/me/notifications/31/read",
            "PUT, /api/me/notifications/read-all" })
    void 로그인하지_않으면_ProblemDetail_401이고_서비스를_부르지_않는다(String method, String path) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.request(HttpMethod.valueOf(method), path).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(service);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = { "/api/me/notifications/31/read", "/api/me/notifications/read-all" })
    void CSRF_토큰이_없으면_ProblemDetail_403이고_서비스를_부르지_않는다(String path) throws Exception {
        mockMvc.perform(put(path).with(loggedIn()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }

    private static RequestPostProcessor loggedIn() {
        LoginMember member = new LoginMember(MEMBER_ID, "me@example.com", MemberRole.USER, null);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(member, null, member.getAuthorities()));
    }
}
