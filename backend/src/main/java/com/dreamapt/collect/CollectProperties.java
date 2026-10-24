package com.dreamapt.collect;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 수집 설정. missing 표시 보류 조건: 새 missing ≥ missingMinCount 그리고
 * 새 missing / 기존 유효 행 > missingRatioLimit (ADR 004 3-⑤).
 */
@ConfigurationProperties(prefix = "dreamapt.collect")
public record CollectProperties(double missingRatioLimit, int missingMinCount) {
}
