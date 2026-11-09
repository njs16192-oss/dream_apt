package com.dreamapt.member;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 로그인한 회원. 세션에 들어가는 principal이라 엔티티 대신 필요한 값만 담는다 (PR 4부터 세션이 DB에 직렬화된다).
 * <p>
 * 비밀번호 해시는 인증할 때만 쓰고, 인증 뒤 ProviderManager가 {@link #eraseCredentials()}를 불러 지운다.
 * 그래서 세션에는 해시가 남지 않는다. 권한은 로그인할 때의 값이라, psql로 role을 바꾸면 다시 로그인해야 적용된다.
 */
public class LoginMember implements UserDetails, CredentialsContainer {

    private static final long serialVersionUID = 1L;

    private final Long id;
    private final String email;
    private final MemberRole role;
    private String passwordHash;

    public LoginMember(Long id, String email, MemberRole role, String passwordHash) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.passwordHash = passwordHash;
    }

    static LoginMember from(Member member) {
        return new LoginMember(member.getId(), member.getEmail(), member.getRole(), member.getPasswordHash());
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public MemberRole getRole() {
        return role;
    }

    /** hasRole("ADMIN")은 "ROLE_ADMIN" 권한을 찾는다 */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
