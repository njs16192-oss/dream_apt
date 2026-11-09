package com.dreamapt.member;

/** 로그인한 회원 정보. 비밀번호 해시는 내보내지 않는다 */
public record MeResponse(Long id, String email, MemberRole role) {

    static MeResponse from(LoginMember member) {
        return new MeResponse(member.getId(), member.getEmail(), member.getRole());
    }
}
