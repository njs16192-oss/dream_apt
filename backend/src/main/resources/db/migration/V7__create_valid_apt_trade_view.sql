-- ADR 007: 조회 기준. 모든 조회는 이 뷰를 쓴다

-- 유효 거래(cancelled = false AND missing_since IS NULL) + 구 코드 + 면적 그룹 + 일괄 거래 표시
-- - area_group: 전용면적 정수 반올림. numeric ROUND는 0.5를 0에서 먼 쪽으로 올리므로 양수 면적에서는 HALF_UP
-- - is_bulk: 같은 단지·같은 계약일 유효 거래 10건 이상. 해제·사라진 거래는 세지 않는다
-- - 윈도 파티션에 sgg_cd를 넣어서(단지가 정해지면 구도 정해지므로 결과는 같다)
--   sgg_cd·apt_seq·deal_date 조건이 윈도 계산 전에 적용되게 한다
CREATE VIEW valid_apt_trade AS
SELECT t.id,
       c.sgg_cd,
       t.apt_seq,
       t.deal_date,
       t.deal_amount,
       t.exclu_use_ar,
       ROUND(t.exclu_use_ar)::int AS area_group,
       t.floor,
       t.apt_dong,
       t.buyer_gbn,
       t.seller_gbn,
       t.dealing_gbn,
       t.agent_sgg_nm,
       t.registered_date,
       COUNT(*) OVER (PARTITION BY c.sgg_cd, t.apt_seq, t.deal_date) >= 10 AS is_bulk
FROM apt_trade t
JOIN apt_complex c ON c.apt_seq = t.apt_seq
WHERE t.cancelled = false
  AND t.missing_since IS NULL;

COMMENT ON VIEW valid_apt_trade IS '유효 거래 + 면적 그룹(area_group) + 일괄 거래(is_bulk). ADR 007';
COMMENT ON COLUMN valid_apt_trade.area_group IS '면적 그룹: 전용면적(㎡) 정수 반올림';
COMMENT ON COLUMN valid_apt_trade.is_bulk IS '같은 단지·같은 계약일 유효 거래 10건 이상';

-- 단지명 검색 (띄어쓰기·일부 오타 허용). pg_trgm은 trusted 확장이라 DB 소유자가 만들 수 있다
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_complex_name_trgm ON apt_complex USING gin (name gin_trgm_ops);
