-- ADR 002: 실거래가 데이터 테이블 설계

CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE apt_complex (
    apt_seq       VARCHAR(20)  PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    sgg_cd        CHAR(5)      NOT NULL,
    umd_cd        CHAR(5)      NOT NULL,
    umd_nm        VARCHAR(50),
    jibun         VARCHAR(20),
    road_address  VARCHAR(200),
    build_year    SMALLINT,
    location      GEOMETRY(Point, 4326),
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE apt_trade (
    id               BIGSERIAL PRIMARY KEY,
    apt_seq          VARCHAR(20)  NOT NULL REFERENCES apt_complex(apt_seq),
    deal_date        DATE         NOT NULL,
    deal_amount      INTEGER      NOT NULL,
    exclu_use_ar     NUMERIC(7,2) NOT NULL,
    floor            SMALLINT     NOT NULL,
    apt_dong         VARCHAR(20),
    buyer_gbn        VARCHAR(10),
    seller_gbn       VARCHAR(10),
    dealing_gbn      VARCHAR(10),
    agent_sgg_nm     VARCHAR(50),
    cancelled        BOOLEAN      NOT NULL DEFAULT false,
    cancel_date      DATE,
    registered_date  DATE,
    created_at       TIMESTAMP NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_apt_trade UNIQUE (apt_seq, deal_date, exclu_use_ar, floor, deal_amount)
);

COMMENT ON COLUMN apt_trade.deal_amount IS '거래금액, 단위: 만원';
COMMENT ON COLUMN apt_trade.exclu_use_ar IS '전용면적(㎡)';

-- 단지·면적별 가격 추이 조회용
CREATE INDEX idx_trade_complex_area_date ON apt_trade (apt_seq, exclu_use_ar, deal_date);
