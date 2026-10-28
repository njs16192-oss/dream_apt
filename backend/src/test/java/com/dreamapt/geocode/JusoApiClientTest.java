package com.dreamapt.geocode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.SocketTimeoutException;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.dreamapt.geocode.JusoApiException.Kind;

/**
 * HTTP 호출 실패의 종류 분류와 키 확인. JSON 오류 응답의 분류는 {@link JusoResponseParserTest}.
 */
class JusoApiClientTest {

    @Test
    void 타임아웃과_5xx는_일시_오류다() {
        assertThat(JusoApiClient.errorKind(new ResourceAccessException("timeout", new SocketTimeoutException())))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(JusoApiClient.errorKind(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", null, null, null)))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    void 인증_실패는_AUTH_그_밖의_4xx는_OTHER() {
        assertThat(JusoApiClient.errorKind(clientError(HttpStatus.UNAUTHORIZED))).isEqualTo(Kind.AUTH);
        assertThat(JusoApiClient.errorKind(clientError(HttpStatus.FORBIDDEN))).isEqualTo(Kind.AUTH);
        assertThat(JusoApiClient.errorKind(clientError(HttpStatus.BAD_REQUEST))).isEqualTo(Kind.OTHER);
    }

    @Test
    void 키가_하나라도_없으면_호출하지_않고_AUTH() {
        JusoApiClient noCoordKey = client("search-key", " ");

        assertThat(noCoordKey.hasKeys()).isFalse();
        assertThatThrownBy(() -> noCoordKey.search("송파구 송파대로 345"))
                .isInstanceOfSatisfying(JusoApiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH))
                .hasMessageContaining("JUSO_COORD_API_KEY");
        assertThat(client(null, "coord-key").hasKeys()).isFalse();
        assertThat(client("search-key", "coord-key").hasKeys()).isTrue();
    }

    private static JusoApiClient client(String searchKey, String coordKey) {
        // 호출 전에 키를 확인하므로 닿지 않는 주소를 써도 된다
        return new JusoApiClient(new JusoProperties("http://localhost:1", searchKey, coordKey, Duration.ZERO),
                new JusoResponseParser());
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, "", null, null, null);
    }
}
