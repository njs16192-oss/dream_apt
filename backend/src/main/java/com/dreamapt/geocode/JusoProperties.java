package com.dreamapt.geocode;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 행정안전부 도로명주소 API 설정 (ADR 010). 검색 API와 좌표제공 API는 승인키를 따로 발급받는다.
 *
 * @param requestInterval API 호출 사이 최소 간격. 검색·좌표 호출을 합쳐서 센다
 */
@ConfigurationProperties(prefix = "dreamapt.juso")
public record JusoProperties(String baseUrl, String searchApiKey, String coordApiKey, Duration requestInterval) {

    public boolean hasKeys() {
        return searchApiKey != null && !searchApiKey.isBlank() && coordApiKey != null && !coordApiKey.isBlank();
    }
}
