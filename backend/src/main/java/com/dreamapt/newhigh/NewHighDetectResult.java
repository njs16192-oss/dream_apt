package com.dreamapt.newhigh;

import java.time.LocalDate;

/**
 * @param from     판단한 계약일 범위의 시작 (포함)
 * @param recorded 이번 실행에서 새로 기록한 신고가 수
 */
public record NewHighDetectResult(LocalDate from, int recorded) {
}
