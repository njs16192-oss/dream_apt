-- ADR 010: 단지 좌표 지오코딩
-- 실패한 단지를 매번 다시 호출하지 않도록 마지막 시도 시각과 실패 사유를 남긴다

ALTER TABLE apt_complex
    ADD COLUMN geocode_attempted_at TIMESTAMPTZ,
    ADD COLUMN geocode_error        VARCHAR(30);

COMMENT ON COLUMN apt_complex.location IS '주출입구 좌표 (WGS84). 행안부 좌표제공 API의 UTM-K 좌표를 변환해 저장 (ADR 010)';
COMMENT ON COLUMN apt_complex.geocode_attempted_at IS '마지막 지오코딩 시도 시각 (성공·실패 모두). 성공 시각이 아니다. NULL이면 아직 시도하지 않음';
COMMENT ON COLUMN apt_complex.geocode_error IS '마지막 지오코딩 실패 사유 (NOT_FOUND, MISMATCH, NO_COORD, API_ERROR). 성공하면 NULL';
