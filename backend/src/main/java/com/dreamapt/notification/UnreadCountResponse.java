package com.dreamapt.notification;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "안 읽은 알림 수")
public record UnreadCountResponse(
        @Schema(description = "안 읽은 알림 수. 목록에서 숨겨지는 알림은 세지 않는다", example = "3") long count) {
}
