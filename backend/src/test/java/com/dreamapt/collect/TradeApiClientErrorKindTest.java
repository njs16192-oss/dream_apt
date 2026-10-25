package com.dreamapt.collect;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.dreamapt.collect.TradeApiException.Kind;

/**
 * HTTP 호출 실패의 종류 분류 (ADR 006 3-②). XML 오류 응답의 분류는 {@link TradeXmlParserTest}.
 */
class TradeApiClientErrorKindTest {

    @Test
    void 타임아웃과_5xx는_일시_오류다() {
        assertThat(TradeApiClient.errorKind(new ResourceAccessException("timeout", new SocketTimeoutException())))
                .isEqualTo(Kind.TRANSIENT);
        assertThat(TradeApiClient.errorKind(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", null, null, null)))
                .isEqualTo(Kind.TRANSIENT);
    }

    @Test
    void 호출_한도_초과와_인증_실패는_실행을_멈춘다() {
        assertThat(TradeApiClient.errorKind(clientError(HttpStatus.TOO_MANY_REQUESTS))).isEqualTo(Kind.QUOTA_EXCEEDED);
        assertThat(TradeApiClient.errorKind(clientError(HttpStatus.UNAUTHORIZED))).isEqualTo(Kind.AUTH);
        assertThat(TradeApiClient.errorKind(clientError(HttpStatus.FORBIDDEN))).isEqualTo(Kind.AUTH);
    }

    @Test
    void 그_밖의_4xx는_그_구월만_실패다() {
        assertThat(TradeApiClient.errorKind(clientError(HttpStatus.BAD_REQUEST))).isEqualTo(Kind.OTHER);
    }

    private static HttpClientErrorException clientError(HttpStatus status) {
        return HttpClientErrorException.create(status, "", null, null, null);
    }
}
