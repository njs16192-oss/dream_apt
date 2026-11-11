package com.dreamapt.notification;

import static com.dreamapt.common.ApiDescriptions.MANWON;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/** 알림 한 건: 관심단지의 신고가 거래 (ADR 011 ⑥) */
@Schema(description = "신고가 알림. 2020년 이후 같은 단지·면적 그룹의 최고가를 넘은 거래")
public record NotificationItem(
        @Schema(description = "알림 ID") long id,
        @Schema(description = "단지 ID", example = "11710-8865") String aptSeq,
        @Schema(description = "단지명", example = "헬리오시티") String complexName,
        @Schema(description = "면적 그룹: 전용면적(㎡)을 정수로 반올림한 값", example = "85") int areaGroup,
        @Schema(description = "전용면적 원값(㎡)", example = "84.99") BigDecimal exclusiveArea,
        @Schema(description = "층", example = "12") int floor,
        @Schema(description = "계약일", example = "2026-10-12") LocalDate dealDate,
        @Schema(description = "거래 금액. " + MANWON, example = "245000") int dealAmount,
        @Schema(description = "신고가로 판단할 당시, 더 이른 계약일 거래의 최고가. " + MANWON, example = "238000")
        int previousMaxAmount,
        @Schema(description = "알림을 만든 시각") OffsetDateTime createdAt,
        @Schema(description = "읽었는지") boolean read) {
}
