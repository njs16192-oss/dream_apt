package com.dreamapt.member;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 로그인한 회원 자신의 정보. 회원 id는 URL이 아니라 세션에서 꺼낸다 */
@Tag(name = "내 정보", description = "로그인 필요. 로그인하지 않았으면 401")
@RestController
@RequestMapping("/api/me")
public class MeController {

    @Operation(summary = "내 정보", description = "로그인할 때의 값이다. 권한(role)을 바꾸면 다시 로그인해야 반영된다")
    @GetMapping
    public MeResponse me(@AuthenticationPrincipal LoginMember member) {
        return MeResponse.from(member);
    }
}
