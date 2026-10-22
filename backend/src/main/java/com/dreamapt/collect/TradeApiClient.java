package com.dreamapt.collect;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
    private final String apiKey;

    public TradeApiClient(DataGoKrProperties properties, TradeXmlParser parser) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.parser = parser;
        this.apiKey = properties.apiKey();
    }

    public List<TradeRecord> fetch(String sggCd, String dealYm) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new TradeApiException("API 키가 없습니다. 환경변수 DATA_GO_KR_API_KEY를 설정하세요.");
        }

        List<TradeRecord> all = new ArrayList<>();
        for (int pageNo = 1; pageNo <= MAX_PAGES; pageNo++) {
            TradePage page = parser.parse(requestPage(sggCd, dealYm, pageNo));
            all.addAll(page.items());
            log.info("실거래가 조회 sggCd={} dealYm={} page={} items={} total={}",
                    sggCd, dealYm, pageNo, page.items().size(), page.totalCount());
            if (page.items().isEmpty() || all.size() >= page.totalCount()) {
                return all;
            }
        }
        throw new TradeApiException("페이지 수가 " + MAX_PAGES + "를 넘었습니다: sggCd=" + sggCd + ", dealYm=" + dealYm);
    }

    private String requestPage(String sggCd, String dealYm, int pageNo) {
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
            throw new TradeApiException("실거래가 API 호출 실패: sggCd=" + sggCd + ", dealYm=" + dealYm, e);
        }
    }
}
