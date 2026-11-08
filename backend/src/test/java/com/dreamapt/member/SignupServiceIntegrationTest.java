package com.dreamapt.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willReturn;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.dreamapt.common.BadRequestException;
import com.dreamapt.common.ConflictException;
import com.dreamapt.support.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SignupServiceIntegrationTest {

    private static final String PASSWORD = "password123";

    @Autowired
    SignupService service;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    JdbcTemplate jdbc;

    /** 동시 가입 경쟁을 만들 때만 existsByEmail을 바꾼다. 나머지 테스트에서는 실제 메서드가 그대로 불린다 */
    @MockitoSpyBean
    MemberRepository memberRepository;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM member");
    }

    @Test
    void 이메일은_앞뒤_공백을_지우고_소문자로_저장한다() {
        service.signup("  User@Example.COM ", PASSWORD);

        assertThat(jdbc.queryForObject("SELECT email FROM member", String.class)).isEqualTo("user@example.com");
    }

    @Test
    void 비밀번호는_bcrypt_해시로_저장하고_원문으로_확인된다() {
        Member member = service.signup("a@b.com", PASSWORD);

        Map<String, Object> row = jdbc.queryForMap("SELECT password_hash, role FROM member WHERE id = ?", member.getId());
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("{bcrypt}").isNotEqualTo(PASSWORD).doesNotContain(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, hash)).isTrue();
        assertThat(passwordEncoder.matches("password124", hash)).isFalse();
        assertThat(row.get("role")).isEqualTo("USER");
    }

    @Test
    void 대소문자만_다른_이메일은_중복이다() {
        service.signup("a@b.com", PASSWORD);

        assertThatThrownBy(() -> service.signup("A@B.com", PASSWORD))
                .isInstanceOf(ConflictException.class)
                .hasMessage("이미 가입된 이메일입니다");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Integer.class)).isEqualTo(1);
    }

    /** existsByEmail 확인과 저장 사이에 같은 이메일이 먼저 가입한 상황: 확인은 통과했지만 unique 제약에 걸린다 */
    @Test
    void 확인과_저장_사이에_끼어든_같은_이메일도_중복이다() {
        service.signup("a@b.com", PASSWORD);
        willReturn(false).given(memberRepository).existsByEmail("a@b.com");

        assertThatThrownBy(() -> service.signup("a@b.com", PASSWORD))
                .isInstanceOf(ConflictException.class)
                .hasMessage("이미 가입된 이메일입니다");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Integer.class)).isEqualTo(1);
    }

    @Test
    void 비밀번호가_72바이트를_넘으면_400이다() {
        String korean25 = "가".repeat(25); // 25자, UTF-8 75바이트

        assertThatThrownBy(() -> service.signup("a@b.com", korean25))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("72바이트");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Integer.class)).isZero();
    }

    @Test
    void 비밀번호가_딱_72바이트면_가입된다() {
        String korean24 = "가".repeat(24); // 72바이트

        Member member = service.signup("a@b.com", korean24);

        assertThat(passwordEncoder.matches(korean24, member.getPasswordHash())).isTrue();
    }
}
