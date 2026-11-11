-- ADR 011 ⑥: 화면 안 알림. 신고가가 새로 기록될 때 그 단지를 관심단지로 둔 회원마다 한 행을 만든다 (쓸 때 나눠주기)
-- 단지·면적·금액은 new_high_event → apt_trade에서 얻는다. 보여줄 때 valid_apt_trade와 다시 조인해 지금도 유효·비일괄인 것만 보여준다
-- new_high_event는 지우지 않으므로 그쪽 FK에는 CASCADE를 두지 않는다. 회원 삭제 시에는 알림도 지운다 (ADR 011 4. 회원 탈퇴)

CREATE TABLE notification (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    member_id          BIGINT       NOT NULL REFERENCES member(id) ON DELETE CASCADE,
    new_high_event_id  BIGINT       NOT NULL REFERENCES new_high_event(id),
    created_at         TIMESTAMPTZ  NOT NULL,
    read_at            TIMESTAMPTZ,
    CONSTRAINT uq_notification_member_event UNIQUE (member_id, new_high_event_id) -- 같은 신고가로 같은 회원에게 두 번 알리지 않는다
);

-- 내 알림 목록 (최신순)
CREATE INDEX idx_notification_member_created ON notification (member_id, created_at DESC);

-- 알림 생성 때 단지로 관심 회원을 찾는다. PK (member_id, apt_seq)는 앞 컬럼이 member_id라 이 조회에 쓸 수 없다
CREATE INDEX idx_watchlist_apt_seq ON watchlist (apt_seq);

COMMENT ON TABLE notification IS '회원별 신고가 알림 (ADR 011 ⑥)';
COMMENT ON COLUMN notification.created_at IS '알림을 만든 시각 (= 신고가를 처음 기록한 실행)';
COMMENT ON COLUMN notification.read_at IS '처음 읽은 시각. NULL이면 안 읽음';
