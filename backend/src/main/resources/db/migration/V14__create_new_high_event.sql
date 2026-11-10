-- ADR 011 ⑤: 신고가 기록. 발견 당시의 스냅샷이라 나중에 조건이 바뀌어도 지우지 않는다
-- 단지·면적·금액은 거래에서 얻는다. 보여줄 때 valid_apt_trade와 다시 조인해 여전히 유효·비일괄인 것만 보여준다
-- 거래는 삭제하지 않으므로(missing_since 표시, ADR 004) FK에 CASCADE를 두지 않는다

CREATE TABLE new_high_event (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trade_id             BIGINT       NOT NULL REFERENCES apt_trade(id),
    previous_max_amount  INTEGER      NOT NULL,
    detected_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_new_high_event_trade UNIQUE (trade_id) -- 다시 돌려도 중복 기록이 없다 (ON CONFLICT DO NOTHING)
);

COMMENT ON TABLE new_high_event IS '신고가 거래 기록 (ADR 011 ⑤). 발견 당시의 스냅샷';
COMMENT ON COLUMN new_high_event.previous_max_amount IS '발견 당시, 같은 그룹에서 더 이른 계약일 거래의 최고가 (만원)';
COMMENT ON COLUMN new_high_event.detected_at IS '처음 신고가로 판단한 시각. 계약일이 아니다';
