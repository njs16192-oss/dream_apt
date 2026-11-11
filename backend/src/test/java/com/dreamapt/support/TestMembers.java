package com.dreamapt.support;

import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트용 회원 행. 로그인은 하지 않으므로 비밀번호 해시는 아무 값이다 */
public final class TestMembers {

    private TestMembers() {
    }

    public static long insert(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("""
                INSERT INTO member (email, password_hash, created_at)
                VALUES (?, '{noop}unused', now())
                RETURNING id
                """, Long.class, email);
    }
}
