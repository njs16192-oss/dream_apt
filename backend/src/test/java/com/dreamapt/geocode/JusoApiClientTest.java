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
    void 각_호출은_자기_키만_확인한다() {
        JusoApiClient searchKeyOnly = client("search-key", " ");

        // 검색 키만 있어도 검색은 호출까지 간다 (닿지 않는 주소라 연결 실패 = 일시 오류)
        assertThatThrownBy(() -> searchKeyOnly.search("송파구 송파대로 345"))
                .isInstanceOfSatisfying(JusoApiException.class, e -> assertThat(e.kind()).isEqualTo(Kind.TRANSIENT));
        assertThatThrownBy(() -> searchKeyOnly.coord(HELIO))
                .isInstanceOfSatisfying(JusoApiException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.MISSING_KEY))
                .hasMessageContaining("JUSO_COORD_API_KEY");

        assertThatThrownBy(() -> client(null, "coord-key").search("송파구 송파대로 345"))
                .isInstanceOfSatisfying(JusoApiException.class,
                        e -> assertThat(e.kind()).isEqualTo(Kind.MISSING_KEY))
                .hasMessageContaining("JUSO_SEARCH_API_KEY");
    }

    @Test
    void hasKeys는_두_키가_모두_있어야_true() {
        assertThat(client("search-key", " ").hasKeys()).isFalse();
        assertThat(client(null, "coord-key").hasKeys()).isFalse();
        assertThat(client("search-key", "coord-key").hasKeys()).isTrue();
    }

    private static final JusoAddress HELIO = new JusoAddress("1171010700", "117102005011", "0", "345", "0",
            "서울특별시 송파구 송파대로 345 (가락동, 헬리오시티)", "송파대로", "0", "913", "0");

    private static JusoApiClient client(String searchKey, String coordKey) {
        // 실제 호출이 일어나도 바로 연결 실패하도록 닿지 않는 주소를 쓴다
        return new JusoApiClient(new JusoProperties("http://localhost:1", searchKey, coordKey, Duration.ZERO),
                new JusoResponseParser());
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, "", null, null, null);
    }
}
