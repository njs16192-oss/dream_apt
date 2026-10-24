-- ADR 004: 거래를 구×월 단위로 대조(reconcile)한다

-- 같은 키의 서로 다른 거래(일괄 매매 등)를 모두 저장하기 위해 unique 제약을 없앤다.
-- 같은 구성의 일반 인덱스는 대조 시 구×월·그룹 조회에 쓴다.
ALTER TABLE apt_trade DROP CONSTRAINT uk_apt_trade;
CREATE INDEX idx_trade_group ON apt_trade (apt_seq, deal_date, exclu_use_ar, floor, deal_amount);

ALTER TABLE apt_trade ADD COLUMN missing_since TIMESTAMP;

COMMENT ON COLUMN apt_trade.missing_since IS '원본(API)에서 사라진 것이 처음 확인된 시각. 다시 나타나면 null. 유효 거래 = cancelled = false AND missing_since IS NULL';
