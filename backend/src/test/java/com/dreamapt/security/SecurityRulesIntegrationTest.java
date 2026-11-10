package com.dreamapt.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.dreamapt.collect.batch.CollectJobLauncher;
import com.dreamapt.collect.batch.CollectTargetRunner;
import com.dreamapt.collect.batch.RunningCollectJobs;
import com.dreamapt.geocode.ComplexGeocodeService;
import com.dreamapt.member.LoginMember;
import com.dreamapt.member.MemberRole;
import com.dreamapt.support.TestcontainersConfiguration;

/**
 * 경로 × 사용자 표 (ADR 011 경로 규칙). 칸마다 보안 규칙만 본다.
 * <ul>
 * <li>pass: 보안 필터를 통과했다 (401·403이 아니다). 그 뒤 컨트롤러가 주는 200·400·404 등은 여기서 보지 않는다</li>
 * <li>401: 로그인 필요, 403: 권한 없음. 둘 다 ProblemDetail(application/problem+json)이어야 한다</li>
 * </ul>
 * 규칙 하나를 빼거나 바꾸면 그 규칙의 칸만 실패해야 한다. CSRF는 여기서는 항상 붙이고 LoginIntegrationTest에서 따로 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityRulesIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    // 관리 API가 통과했을 때 실제 수집·지오코딩이 돌지 않게 막는다
    @MockitoBean
    CollectTargetRunner collectTargetRunner;

    @MockitoBean
    CollectJobLauncher collectJobLauncher;

    @MockitoBean
    RunningCollectJobs runningCollectJobs;

    @MockitoBean
    ComplexGeocodeService complexGeocodeService;

    @ParameterizedTest(name = "{0} {1} → 비로그인 {2}, USER {3}, ADMIN {4}")
    @CsvSource({
            // 조회 GET은 모두 허용
            "GET,    /api/ping,                     pass, pass, pass",
            "GET,    /api/regions,                  pass, pass, pass",
            "GET,    /api/complexes/1/trades,       pass, pass, pass",
            // API 문서·health
            "GET,    /v3/api-docs/public,           pass, pass, pass",
            "GET,    /swagger-ui.html,              pass, pass, pass",
            "GET,    /actuator/health,              pass, pass, pass",
            // 컨트롤러 밖 오류가 다시 들어오는 경로. 막으면 원래 상태 코드 대신 401이 나간다
            "GET,    /error,                        pass, pass, pass",
            // 가입·로그인·로그아웃
            "POST,   /api/auth/signup,              pass, pass, pass",
            "POST,   /api/auth/login,               pass, pass, pass",
            "POST,   /api/auth/logout,              pass, pass, pass",
            // 내 정보는 로그인
            "GET,    /api/me,                       401,  pass, pass",
            // 관심단지도 로그인 (/api/me/**)
            "GET,    /api/me/watchlist,             401,  pass, pass",
            "PUT,    /api/me/watchlist/11710-1,     401,  pass, pass",
            "DELETE, /api/me/watchlist/11710-1,     401,  pass, pass",
            // 관리 API는 ADMIN만
            "POST,   /api/admin/geocode,            401,  403,  pass",
            "POST,   /api/admin/collect,            401,  403,  pass",
            "GET,    /api/admin/collect/jobs/1,     401,  403,  pass",
            // 규칙에 없는 것은 거부 (조회 경로라도 GET이 아니면, 없는 경로도)
            "POST,   /api/complexes,                401,  403,  403",
            "DELETE, /api/regions,                  401,  403,  403",
            "GET,    /api/unknown,                  401,  403,  403",
            "GET,    /actuator/env,                 401,  403,  403",
    })
    void 경로별_허용_규칙(String method, String path, String anonymous, String user, String admin) throws Exception {
        assertRule(request(HttpMethod.valueOf(method), path), anonymous);
        assertRule(request(HttpMethod.valueOf(method), path).with(loginAs(MemberRole.USER)), user);
        assertRule(request(HttpMethod.valueOf(method), path).with(loginAs(MemberRole.ADMIN)), admin);
    }

    private void assertRule(MockHttpServletRequestBuilder request, String expected) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(request.with(csrf())).andReturn().getResponse();
        String path = request.buildRequest(null).getRequestURI();

        switch (expected) {
            case "pass" -> assertThat(response.getStatus()).as(path).isNotIn(401, 403);
            case "401" -> assertProblem(response, path, 401, SecurityProblemHandler.UNAUTHORIZED);
            case "403" -> assertProblem(response, path, 403, SecurityProblemHandler.FORBIDDEN);
            default -> throw new IllegalArgumentException(expected);
        }
    }

    private static void assertProblem(MockHttpServletResponse response, String path, int status, String detail)
            throws Exception {
        assertThat(response.getStatus()).as(path).isEqualTo(status);
        // 필터에서 직접 쓰므로 Content-Type도 컨트롤러의 ProblemDetail과 같아야 한다
        assertThat(response.getContentType()).as(path).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).as(path)
                .contains("\"status\":" + status)
                .contains("\"detail\":\"" + detail + "\"")
                .contains("\"instance\":\"" + path + "\"");
    }

    private static RequestPostProcessor loginAs(MemberRole role) {
        LoginMember member = new LoginMember(1L, role.name().toLowerCase() + "@example.com", role, null);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(member, null, member.getAuthorities()));
    }
}
