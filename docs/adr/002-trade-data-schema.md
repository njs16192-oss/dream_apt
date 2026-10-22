# 002. 실거래가 데이터 테이블 설계

- 상태: 확정
- 날짜: 2026-10-22
- 데이터 출처: 공공데이터포털 「국토교통부_아파트 매매 실거래가 상세 자료」 (RTMSDataSvcAptTradeDev)

## 결정 요약

| 항목 | 결정 |
|---|---|
| 테이블 구조 | 단지(`apt_complex`)와 거래(`apt_trade`) 2개로 분리 |
| 단지 PK | API의 `aptSeq` (예: `11500-151`) |
| 거래 중복 처리 | unique 키(단지 + 계약일 + 전용면적 + 층 + 금액) + upsert |
| 거래금액 단위 | 만원 단위 `INTEGER` (API 원본 단위 유지) |

---

## 1. 테이블을 2개로 나눈 이유

API 응답은 거래 1건마다 단지 정보(단지명, 주소, 건축년도 등)가 함께 들어온다.
이를 한 테이블에 그대로 저장하면 같은 단지 정보가 거래 수만큼 반복 저장된다.

- **중복 제거**: 단지 정보는 단지당 1번만 저장하고, 거래는 `apt_seq`로 단지를 참조한다.
- **갱신 일관성**: 단지명이나 주소가 바뀌어도 단지 테이블 한 곳만 수정하면 된다.
- **단지 단위 기능 확장**: 지도 좌표(PostGIS `location`), 관심단지 등록, 단지 비교 등은 모두 "단지"가 기준이다. 좌표를 거래마다 저장할 이유가 없다.
- **조회 성능**: "이 단지의 거래 이력"을 `apt_seq` 인덱스로 바로 조회할 수 있다.

### 필드 분류

**단지 (`apt_complex`)**

| API 필드 | 컬럼 | 비고 |
|---|---|---|
| `aptSeq` | `apt_seq` | 단지 고유번호, PK |
| `aptNm` | `name` | |
| `sggCd` / `umdCd` / `umdNm` | `sgg_cd` / `umd_cd` / `umd_nm` | 시군구, 법정동 |
| `jibun`, `roadNm` + `roadNmBonbun` + `roadNmBubun` | `jibun`, `road_address` | 4단계 지오코딩에 사용 |
| `buildYear` | `build_year` | |
| (없음) | `location` | 4단계에서 좌표 변환 후 채움 |

**거래 (`apt_trade`)**

| API 필드 | 컬럼 | 비고 |
|---|---|---|
| `dealYear` / `dealMonth` / `dealDay` | `deal_date` | DATE 하나로 합침 |
| `dealAmount` | `deal_amount` | `"81,700"` → 쉼표 제거 후 정수, 단위 만원 |
| `excluUseAr` | `exclu_use_ar` | 전용면적(㎡) |
| `floor` | `floor` | 지하층은 음수 가능 |
| `aptDong` | `apt_dong` | 등기 완료 후 채워짐 |
| `buyerGbn` / `slerGbn` / `dealingGbn` | `buyer_gbn` / `seller_gbn` / `dealing_gbn` | |
| `estateAgentSggNm` | `agent_sgg_nm` | |
| `cdealType` / `cdealDay` | `cancelled` / `cancel_date` | 해제 거래 여부 |
| `rgstDate` | `registered_date` | 등기일, 나중에 채워짐 |

---

## 2. 설계 포인트

### ① 거래 고유 ID가 없다
API는 거래별 고유 ID를 제공하지 않는다. 그런데 같은 월의 데이터를 여러 번 수집해야 한다.

- 실거래 신고 기한이 계약 후 30일이라, 늦게 신고된 거래가 나중에 추가된다.
- 해제 여부, 등기일, 동 정보가 시간이 지나 갱신된다.

**검토한 방법**
- A. `단지 + 계약일 + 전용면적 + 층 + 금액`을 unique 키로 잡고 upsert
- B. 수집할 때마다 해당 구·해당 월 데이터를 전부 삭제 후 재적재

**결정: A**
- B는 단순하지만 재수집할 때마다 거래 PK가 바뀐다. "신고가 알림을 보낸 거래"처럼 거래를 참조하는 데이터가 깨진다.
- **나중에 채워지는 값(`aptDong`, `rgstDate`, `cdealType`)은 키에 넣지 않는다.** 값이 채워지는 순간 같은 거래가 새 거래로 인식되어 중복이 생기기 때문이다. 이 값들은 upsert 시 갱신 대상이다.
- 한계: 같은 날, 같은 면적, 같은 층, 같은 금액의 서로 다른 동 거래는 하나로 합쳐질 수 있다. 발생 확률이 매우 낮아 허용하되, 실제 사례가 나오면 키 보완을 검토한다.

### ② 빈 값이 null이 아니라 공백 문자열(" ")로 온다
`aptDong`, `cdealType`, `cdealDay`, `rgstDate` 등이 값이 없을 때 `" "`로 내려온다.

- 파싱 시 `trim()` 후 빈 문자열이면 `null`로 변환한다.
- 이 처리를 빠뜨리면 `apt_dong = ' '` 같은 데이터가 쌓여서 조회 조건과 통계가 어긋난다.

### ③ 해제(취소)된 거래를 반드시 구분해야 한다
`cdealType`에 값이 있으면 계약이 해제된 거래다.

- 해제 거래를 그대로 두면 **가짜 신고가**를 잡게 된다. 높은 가격으로 신고했다가 해제하는 사례가 실제로 문제가 된 바 있다.
- `cancelled` 컬럼으로 저장하고, 신고가 판단·가격 추이·통계 쿼리에서는 기본적으로 제외한다.
- 거래 자체는 삭제하지 않는다. 해제 이력도 의미 있는 정보이기 때문이다.

### ④ 면적은 전용면적만 제공된다
`excluUseAr`은 전용면적(㎡)이다. 예: 34.44㎡ ≈ 전용 10.4평.

- 일상적으로 말하는 "○○평형"은 공급면적 기준이라 API 값과 다르다.
- 저장은 전용면적 원본(`NUMERIC(7,2)`) 그대로 한다.
- 화면에서 평형을 어떻게 묶어 보여줄지(전용 기준 구간, 공급면적 환산 등)는 3단계에서 별도로 결정한다.

---

## 3. 스키마

```sql
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
    deal_amount      INTEGER      NOT NULL,   -- 단위: 만원
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

-- 단지·면적별 가격 추이 조회용
CREATE INDEX idx_trade_complex_area_date ON apt_trade (apt_seq, exclu_use_ar, deal_date);
```

## 4. 거래금액 단위: 만원
- API 원본이 만원 단위라 변환 없이 저장할 수 있다.
- 아파트 가격은 만원 단위로 표기하는 것이 관례라 화면 표시에도 자연스럽다.
- `INTEGER` 최대값(약 21억 만원)으로 충분하다.
- 단위 혼동을 막기 위해 컬럼 주석과 엔티티 필드 주석에 "단위: 만원"을 명시한다.
