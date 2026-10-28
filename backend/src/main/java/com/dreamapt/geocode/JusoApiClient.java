package com.dreamapt.geocode;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import com.dreamapt.collect.ApiRequestThrottle;
import com.dreamapt.geocode.JusoApiException.Kind;

/**
 * 행정안전부 도로명주소 API 클라이언트 (ADR 010).
 * 검색 API로 건물을 찾고, 그 건물의 주출입구 좌표를 좌표제공 API로 받는다.
 */
@Component
public class JusoApiClient {

    // 같은 주소에 결과가 여러 건일 수 있다. 법정동이 맞는 첫 건을 고르므로 한 페이지면 충분하다
    private static final int COUNT_PER_PAGE = 10;

    private final RestClient restClient;
    private final JusoResponseParser parser;
    private final ApiRequestThrottle throttle;
    private final JusoProperties properties;

    public JusoApiClient(JusoProperties properties, JusoResponseParser parser) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.parser = parser;
        this.throttle = new ApiRequestThrottle(properties.requestInterval());
        this.properties = properties;
    }

    public boolean hasKeys() {
        return properties.hasKeys();
    }

    public List<JusoAddress> search(String keyword) {
        requireKeys();
        // 값은 URI 변수로 넘긴다. 한글 검색어와 키의 특수문자가 올바르게 인코딩된다
        return parser.parseSearch(get("/addrLinkApi.do", "keyword=" + keyword, uri -> uri
                .queryParam("confmKey", "{key}")
                .queryParam("currentPage", "1")
                .queryParam("countPerPage", "{count}")
                .queryParam("keyword", "{keyword}")
                .queryParam("resultType", "json")
                .build(Map.of("key", properties.searchApiKey(), "count", COUNT_PER_PAGE, "keyword", keyword))));
    }

    public Optional<UtmkPoint> coord(JusoAddress address) {
        requireKeys();
        return parser.parseCoord(get("/addrCoordApi.do", "roadAddr=" + address.roadAddr(), uri -> uri
                .queryParam("confmKey", "{key}")
                .queryParam("admCd", "{admCd}")
                .queryParam("rnMgtSn", "{rnMgtSn}")
                .queryParam("udrtYn", "{udrtYn}")
                .queryParam("buldMnnm", "{buldMnnm}")
                .queryParam("buldSlno", "{buldSlno}")
                .queryParam("resultType", "json")
                .build(Map.of("key", properties.coordApiKey(),
                        "admCd", address.admCd(),
                        "rnMgtSn", address.rnMgtSn(),
                        "udrtYn", address.udrtYn(),
                        "buldMnnm", address.buldMnnm(),
                        "buldSlno", address.buldSlno() == null ? "0" : address.buldSlno()))));
    }

    private String get(String path, String context, Function<UriBuilder, URI> query) {
        throttle.acquire();
        try {
            return restClient.get()
                    .uri(uri -> query.apply(uri.path(path)))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new JusoApiException(errorKind(e), "도로명주소 API 호출 실패: " + path + " " + context, e);
        }
    }

    private void requireKeys() {
        if (!properties.hasKeys()) {
            throw new JusoApiException(Kind.AUTH,
                    "도로명주소 API 키가 없습니다. 환경변수 JUSO_SEARCH_API_KEY, JUSO_COORD_API_KEY를 설정하세요.");
        }
    }

    /** 타임아웃·연결 실패·5xx는 일시 오류. 게이트웨이의 401·403은 키 문제로 본다 */
    static Kind errorKind(RestClientException e) {
        if (e instanceof ResourceAccessException || e instanceof HttpServerErrorException) {
            return Kind.TRANSIENT;
        }
        if (e instanceof HttpClientErrorException clientError
                && (clientError.getStatusCode().value() == 401 || clientError.getStatusCode().value() == 403)) {
            return Kind.AUTH;
        }
        return Kind.OTHER;
    }
}
