-- ADR 003: 단지 정보는 더 최근 거래일 때만 갱신한다
-- 수집은 구×월을 최신 → 과거 순으로 처리해서, 마지막에 처리한 달의 단지명·주소·준공연도가 남았다.
-- 그 값을 가져온 거래의 계약일을 남기고, 그보다 늦은 거래만 단지 정보를 덮게 한다.
-- 기존 행은 NULL로 둔다. NULL이면 어떤 거래든 덮을 수 있어 수집이 돌면서 자연히 채워진다

ALTER TABLE apt_complex ADD COLUMN source_deal_date DATE;

COMMENT ON COLUMN apt_complex.source_deal_date IS
    '단지 정보(이름·주소·준공연도)를 가져온 거래의 계약일. 이보다 늦은 거래만 단지 정보를 갱신한다 (ADR 003). NULL이면 아직 기준 없음';
