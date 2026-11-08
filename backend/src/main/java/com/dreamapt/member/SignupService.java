package com.dreamapt.member;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.dreamapt.common.BadRequestException;
import com.dreamapt.common.ConflictException;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SignupService {

    /** BCrypt는 72바이트까지만 받는다 (넘으면 IllegalArgumentException). @Size는 글자 수라 한글 25자(75바이트)가 통과한다 */
    static final int MAX_PASSWORD_BYTES = 72;

    /** V11의 이메일 unique 제약 이름 */
    static final String EMAIL_UNIQUE_CONSTRAINT = "uq_member_email";

    private static final String DUPLICATE_EMAIL = "이미 가입된 이메일입니다";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public Member signup(String email, String rawPassword) {
        String normalized = email.trim().toLowerCase(Locale.ROOT); // 대소문자·앞뒤 공백 통일 (ADR 011)
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequestException("비밀번호가 너무 깁니다 (UTF-8 기준 최대 " + MAX_PASSWORD_BYTES + "바이트)");
        }
        if (memberRepository.existsByEmail(normalized)) {
            throw new ConflictException(DUPLICATE_EMAIL);
        }
        String hash = passwordEncoder.encode(rawPassword);
        try {
            // flush까지 해서 INSERT가 이 안에서 실행되게 한다 (커밋 때 나면 아래 catch를 지나친다)
            return memberRepository.saveAndFlush(Member.create(normalized, hash));
        } catch (DataIntegrityViolationException e) {
            // 위 확인과 저장 사이에 같은 이메일이 먼저 가입한 경우. 다른 제약 위반은 그대로 던진다
            if (isEmailUniqueViolation(e)) {
                throw new ConflictException(DUPLICATE_EMAIL);
            }
            throw e;
        }
    }

    private static boolean isEmailUniqueViolation(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return EMAIL_UNIQUE_CONSTRAINT.equals(cve.getConstraintName());
            }
        }
        return false;
    }
}
