package com.dreamapt.member;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 비밀번호 해시. 저장값 앞에 {bcrypt}처럼 방식이 붙어서 나중에 알고리즘을 바꿀 수 있다 (ADR 011 ③) */
@Configuration
public class PasswordConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}