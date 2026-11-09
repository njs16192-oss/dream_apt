package com.dreamapt.member;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** 형식 검사(이메일 모양, 8자 이상)는 하지 않는다. 틀리면 그냥 로그인 실패다 */
public record LoginRequest(
        @Schema(example = "user@example.com") @NotBlank String email,
        @Schema(example = "password123") @NotBlank String password) {
}
