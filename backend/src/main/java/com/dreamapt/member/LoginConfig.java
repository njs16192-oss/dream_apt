package com.dreamapt.member;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 이메일·비밀번호 로그인 (ADR 011 ③). AuthController의 로그인이 이 AuthenticationManager를 쓴다 */
@Configuration(proxyBeanMethods = false)
public class LoginConfig {

    /** 가입과 같은 규칙으로 이메일을 정리해서 찾는다 (SignupService) */
    @Bean
    UserDetailsService memberUserDetailsService(MemberRepository memberRepository) {
        return email -> memberRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .map(LoginMember::from)
                .orElseThrow(() -> new UsernameNotFoundException("no member"));
    }

    /**
     * DaoAuthenticationProvider는 없는 이메일도 BadCredentialsException으로 바꿔서 틀린 비밀번호와 구분되지 않게 한다.
     * 없는 이메일일 때도 비밀번호 해시 비교를 한 번 해서 응답 시간으로도 구분하기 어렵게 한다.
     */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
