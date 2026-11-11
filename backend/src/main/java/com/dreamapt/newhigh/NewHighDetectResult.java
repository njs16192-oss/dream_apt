package com.dreamapt.newhigh;

import java.time.LocalDate;

/**
 * @param from     판단한 계약일 범위의 시작 (포함)
 * @param recorded 이번 실행에서 새로 기록한 신고가 수
 * @param notified 이번 실행에서 새로 만든 알림 수 (새 신고가 × 그 단지를 관심단지로 둔 회원)
 */
public record NewHighDetectResult(LocalDate from, int recorded, int notified) {
}
