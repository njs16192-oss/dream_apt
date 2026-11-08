package com.dreamapt.member;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.dreamapt.common.ConflictException;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignupService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Member signup(String email, String rawPassword) {
        String normalized = email.trim().toLowerCase(Locale.ROOT); // 대소문자·앞뒤 공백 통일 (ADR 011)
        if (memberRepository.existsByEmail(normalized)) {
            throw new ConflictException("이미 가입된 이메일입니다");
        }
        String hash = passwordEncoder.encode(rawPassword);
        return memberRepository.save(Member.create(normalized, hash));
    }
}