-- ADR 011: 회원 계정. 이메일은 소문자로 저장한다

CREATE TABLE member (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email               VARCHAR(254)    NOT NULL,
    password_hash       VARCHAR(100)    NOT NULL,
    role                VARCHAR(20)     NOT NULL DEFAULT 'USER',
    created_at          TIMESTAMPTZ     NOT NULL,
    CONSTRAINT uq_member_email UNIQUE (email), -- 동시 가입 경쟁을 409로 바꿀 때 이 이름으로 구분한다 (SignupService)
    CONSTRAINT ck_member_role CHECK (role IN ('USER', 'ADMIN'))
);

COMMENT ON COLUMN member.email IS '로그인 아이디. 소문자로 저장한다';
COMMENT ON COLUMN member.password_hash IS 'BCrypt 해시. 앞에 {bcrypt}가 붙는다 (DelegatingPasswordEncoder)';
COMMENT ON COLUMN member.role IS 'USER 또는 ADMIN. ADMIN은 psql로만 지정한다 (ADR 011)';