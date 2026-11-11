package com.dreamapt.notification;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dreamapt.common.PageResponse;
import com.dreamapt.member.LoginMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/**
 * 내 신고가 알림 (ADR 011 ⑥). 회원 id는 URL이 아니라 세션의 로그인 회원에서 꺼낸다 (IDOR 방지, WatchlistController와 같다).
 */
@Tag(name = "알림", description = "관심단지의 신고가(2020년 이후 최고가) 알림. 로그인 필요. 로그인하지 않았으면 401. "
        + "PUT은 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내야 한다")
@RestController
@RequestMapping("/api/me/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @Operation(summary = "알림 목록",
            description = "최신순. 알림을 만든 뒤 거래가 해제·삭제되었거나 일괄 거래가 된 알림은 보여주지 않는다")
    @GetMapping
    public PageResponse<NotificationItem> notifications(@AuthenticationPrincipal LoginMember member,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "페이지 크기 (1–100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.findPage(member.getId(), page, size);
    }

    @Operation(summary = "안 읽은 알림 수", description = "목록에서 숨겨지는 알림은 세지 않는다")
    @GetMapping("/unread-count")
    public UnreadCountResponse unreadCount(@AuthenticationPrincipal LoginMember member) {
        return new UnreadCountResponse(service.countUnread(member.getId()));
    }

    @Operation(summary = "알림 읽음 처리",
            description = "이미 읽었으면 처음 읽은 상태를 그대로 두고 204. 내 알림이 아니거나 없으면 404")
    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal LoginMember member,
            @Parameter(description = "알림 ID") @PathVariable long id) {
        service.markRead(member.getId(), id);
    }

    @Operation(summary = "알림 모두 읽음 처리", description = "내 안 읽은 알림을 모두 읽음으로 바꾼다")
    @PutMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal LoginMember member) {
        service.markAllRead(member.getId());
    }
}
