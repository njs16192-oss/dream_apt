package com.dreamapt.collect;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공데이터포털 API 설정. apiKey는 포털의 "Decoding" 키를 넣는다.
 *
 * @param requestInterval API 호출 사이 최소 간격 (ADR 006 3-②)
 */
@ConfigurationProperties(prefix = "dreamapt.data-go-kr")
public record DataGoKrProperties(String baseUrl, String apiKey, Duration requestInterval) {
}
