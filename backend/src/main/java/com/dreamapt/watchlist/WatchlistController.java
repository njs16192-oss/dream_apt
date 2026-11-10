package com.dreamapt.watchlist;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dreamapt.member.LoginMember;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * 내 관심단지. 회원 id는 URL이 아니라 세션의 로그인 회원에서 꺼낸다.
 * 그래서 URL을 바꿔도 다른 회원의 관심단지에는 닿지 않는다 (IDOR 방지).
 */
@Tag(name = "관심단지", description = "로그인 필요. 로그인하지 않았으면 401. "
        + "PUT·DELETE는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 보내야 한다")
@RestController
@RequestMapping("/api/me/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService service;

    @Operation(summary = "관심단지 목록", description = "최근에 추가한 순서. 최대 " + WatchlistService.MAX_WATCHLIST + "개")
    @GetMapping
    public List<WatchlistItem> list(@AuthenticationPrincipal LoginMember member) {
        return service.findAll(member.getId());
    }

    @Operation(summary = "관심단지 추가",
            description = "이미 있으면 아무것도 바꾸지 않고 204 (여러 번 보내도 결과가 같다). "
                    + "없는 단지는 404, " + WatchlistService.MAX_WATCHLIST + "개가 이미 차 있으면 409")
    @PutMapping("/{aptSeq}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@AuthenticationPrincipal LoginMember member,
            @Parameter(description = "단지 ID", example = "11710-8865") @PathVariable String aptSeq) {
        service.add(member.getId(), aptSeq);
    }

    @Operation(summary = "관심단지 해제", description = "목록에 없어도 204")
    @DeleteMapping("/{aptSeq}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal LoginMember member,
            @Parameter(description = "단지 ID", example = "11710-8865") @PathVariable String aptSeq) {
        service.remove(member.getId(), aptSeq);
    }
}
