package com.dreamapt.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공공데이터포털 API 설정. apiKey는 포털의 "Decoding" 키를 넣는다.
 */
@ConfigurationProperties(prefix = "dreamapt.data-go-kr")
public record DataGoKrProperties(String baseUrl, String apiKey) {
}
