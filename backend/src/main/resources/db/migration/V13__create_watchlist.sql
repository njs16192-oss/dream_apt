CREATE TABLE watchlist (
    member_id   BIGINT  NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    apt_seq     VARCHAR(20) NOT NULL REFERENCES apt_complex(apt_seq),
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_watchlist PRIMARY KEY (member_id, apt_seq)
);
