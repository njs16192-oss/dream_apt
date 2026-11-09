package com.dreamapt.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.dreamapt.support.TestcontainersConfiguration;

/**
 * 세션 쿠키 속성 (ADR 011 ①). MockMvc는 세션 쿠키(SESSION)를 만들지 않으므로 실제 서버(Tomcat)를 띄워 Set-Cookie를 본다.
 */
class SessionCookieIntegrationTest {

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @Import(TestcontainersConfiguration.class)
    class 기본_설정이면 {

        @LocalServerPort
        int port;

        @Test
        void 세션_쿠키는_HttpOnly_SameSite_Lax_Secure() throws Exception {
            String sessionCookie = loginSetCookie(port, "SESSION=");

            assertThat(sessionCookie).contains("HttpOnly").contains("SameSite=Lax").contains("Secure");
        }

        @Test
        void XSRF_TOKEN_쿠키는_JS가_읽어야_하므로_HttpOnly가_아니다() throws Exception {
            String xsrfCookie = setCookie(send(port, HttpRequest.newBuilder(uri(port, "/api/ping")).GET(), null),
                    "XSRF-TOKEN=");

            assertThat(xsrfCookie).doesNotContain("HttpOnly");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("local")
    @Import(TestcontainersConfiguration.class)
    class local_프로필이면 {

        @LocalServerPort
        int port;

        @Test
        void http로_개발하므로_Secure를_끈다() throws Exception {
            String sessionCookie = loginSetCookie(port, "SESSION=");

            assertThat(sessionCookie).contains("HttpOnly").contains("SameSite=Lax").doesNotContain("Secure");
        }
    }

    /** 브라우저처럼 XSRF-TOKEN 쿠키를 받아 가입·로그인하고, 로그인 응답의 Set-Cookie 중 prefix로 시작하는 것을 돌려준다 */
    private static String loginSetCookie(int port, String prefix) throws Exception {
        String xsrf = setCookie(send(port, HttpRequest.newBuilder(uri(port, "/api/ping")).GET(), null), "XSRF-TOKEN=");
        String token = xsrf.substring("XSRF-TOKEN=".length(), xsrf.indexOf(';'));
        String body = "{\"email\":\"cookie-" + UUID.randomUUID() + "@example.com\",\"password\":\"password123\"}";

        HttpResponse<String> signup = send(port, HttpRequest.newBuilder(uri(port, "/api/auth/signup"))
                .POST(HttpRequest.BodyPublishers.ofString(body)), token);
        assertThat(signup.statusCode()).isEqualTo(201);
        HttpResponse<String> login = send(port, HttpRequest.newBuilder(uri(port, "/api/auth/login"))
                .POST(HttpRequest.BodyPublishers.ofString(body)), token);
        assertThat(login.statusCode()).isEqualTo(200);
        return setCookie(login, prefix);
    }

    private static HttpResponse<String> send(int port, HttpRequest.Builder request, String xsrfToken)
            throws Exception {
        if (xsrfToken != null) {
            request.header("Content-Type", "application/json")
                    .header("Cookie", "XSRF-TOKEN=" + xsrfToken)
                    .header("X-XSRF-TOKEN", xsrfToken);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String setCookie(HttpResponse<?> response, String prefix) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        return cookies.stream().filter(c -> c.startsWith(prefix)).findFirst()
                .orElseThrow(() -> new AssertionError(prefix + " Set-Cookie 없음: " + cookies));
    }

    private static URI uri(int port, String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
