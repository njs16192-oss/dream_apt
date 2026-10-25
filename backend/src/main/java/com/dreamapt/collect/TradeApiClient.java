package com.dreamapt.collect;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.dreamapt.collect.TradeApiException.Kind;

import lombok.extern.slf4j.Slf4j;

/**
 * 국토교통부 아파트 매매 실거래가 상세 API 클라이언트.
 * 한 시군구·한 달 치를 페이지를 넘기며 전부 가져온다.
 */
@Slf4j
@Component
public class TradeApiClient {

    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 100; // 무한 루프 방지

    private final RestClient restClient;
    private final TradeXmlParser parser;
    private final ApiRequestThrottle throttle;
    private final String apiKey;

    public TradeApiClient(DataGoKrProperties properties, TradeXmlParser parser, ApiRequestThrottle throttle) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.parser = parser;
        this.throttle = throttle;
        this.apiKey = properties.apiKey();
    }

    public FetchedTrades fetch(String sggCd, String dealYm) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new TradeApiException(Kind.AUTH, "API 키가 없습니다. 환경변수 DATA_GO_KR_API_KEY를 설정하세요.");
        }

        List<TradeRecord> all = new ArrayList<>();
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            TradePage page = parser.parse(requestPage(sggCd, dealYm, pageNo));
            all.addAll(page.items());
            log.info("실거래가 조회 sggCd={} dealYm={} page={} items={} total={}",
                    sggCd, dealYm, pageNo, page.items().size(), page.totalCount());
            if (page.items().isEmpty() || all.size() >= page.totalCount()) {
                return new FetchedTrades(all, page.totalCount());
            }
        }
        throw new TradeApiException("페이지 수가 " + MAX_PAGES + "를 넘었습니다: sggCd=" + sggCd + ", dealYm=" + dealYm);
    }

    private String requestPage(String sggCd, String dealYm, int pageNo) {
        throttle.acquire();
        try {
            // 값은 반드시 URI 변수({...})로 넘긴다. 쿼리 문자열에 직접 넣으면 키의 '+'가
            // 인코딩되지 않아 서버에서 공백으로 해석되고 인증에 실패한다.
            return restClient.get()
                    .uri(uri -> uri.path("/getRTMSDataSvcAptTradeDev")
                            .queryParam("serviceKey", "{serviceKey}")
                            .queryParam("LAWD_CD", "{lawdCd}")
                            .queryParam("DEAL_YMD", "{dealYmd}")
                            .queryParam("pageNo", "{pageNo}")
                            .queryParam("numOfRows", "{numOfRows}")
                            .build(Map.of(
                                    "serviceKey", apiKey,
                                    "lawdCd", sggCd,
                                    "dealYmd", dealYm,
                                    "pageNo", pageNo,
                                    "numOfRows", PAGE_SIZE)))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new TradeApiException(errorKind(e), "실거래가 API 호출 실패: sggCd=" + sggCd + ", dealYm=" + dealYm, e);
        }
    }

    /**
     * 타임아웃·연결 실패·5xx는 일시 오류. 게이트웨이가 HTTP 상태로 알려 주는 인증 실패(401·403)와
     * 호출 한도 초과(429)는 XML 오류 응답과 같은 종류로 본다.
     */
    static Kind errorKind(RestClientException e) {
        if (e instanceof ResourceAccessException || e instanceof HttpServerErrorException) {
            return Kind.TRANSIENT;
        }
        if (e instanceof HttpClientErrorException clientError) {
            if (clientError.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
                return Kind.QUOTA_EXCEEDED;
            }
            if (clientError.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)
                    || clientError.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                return Kind.AUTH;
            }
        }
        return Kind.OTHER;
    }
}
