-- ADR 006: 구×월별 최신 수집 상태. 실행별 이력이 아니라 구×월당 1행이다 (실행 이력은 BATCH_* 테이블)
-- backfill 이어하기(성공한 구×월 건너뛰기), 모니터링, 화면의 "데이터 기준일"에 쓴다

CREATE TABLE collect_history (
    sgg_cd             CHAR(5)     NOT NULL,
    deal_ym            CHAR(6)     NOT NULL,
    status             VARCHAR(10) NOT NULL,
    last_attempted_at  TIMESTAMPTZ NOT NULL,
    last_succeeded_at  TIMESTAMPTZ,
    fetched            INTEGER,
    inserted           INTEGER,
    updated            INTEGER,
    unchanged          INTEGER,
    missing            INTEGER,
    warnings           TEXT[]      NOT NULL DEFAULT '{}',
    failure_reason     TEXT,
    api_calls          INTEGER     NOT NULL DEFAULT 0,
    job_execution_id   BIGINT,
    PRIMARY KEY (sgg_cd, deal_ym),
    CONSTRAINT ck_collect_history_status CHECK (status IN ('SUCCESS', 'FAILED'))
);

COMMENT ON COLUMN collect_history.deal_ym IS '계약월 YYYYMM (API의 DEAL_YMD)';
COMMENT ON COLUMN collect_history.status IS '마지막 시도의 결과';
COMMENT ON COLUMN collect_history.last_succeeded_at IS '마지막 성공 시각. 실패해도 유지된다';
COMMENT ON COLUMN collect_history.fetched IS '결과 건수(fetched~missing, warnings)는 마지막 성공 기준';
COMMENT ON COLUMN collect_history.failure_reason IS '마지막 시도가 실패했을 때의 사유. 성공하면 null';
COMMENT ON COLUMN collect_history.api_calls IS '마지막 시도의 API 호출 수 (재시도 포함)';
COMMENT ON COLUMN collect_history.job_execution_id IS '마지막 시도를 한 배치 실행. 관리 API 단건 수집이면 null';
