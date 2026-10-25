# scripts

개발·분석용 보조 스크립트. 서비스 코드(`backend/`, `frontend/`)와는 독립적으로 실행한다.

## analysis/key_collision.py — 거래 unique 키 충돌 분석

ADR 002의 거래 unique 키 `(aptSeq, 계약일, 전용면적, 층, 금액)`를 실거래가 API 원본에 적용해서, 키가 겹치는 레코드 그룹을 찾고 유형별로 분류한다.
결과는 [docs/analysis/2026-10-key-collision.md](../docs/analysis/2026-10-key-collision.md)에 정리되어 있다.

### 요구 사항

- Python 3.10 이상, 표준 라이브러리만 사용 (설치할 패키지 없음)
- 환경변수 `DATA_GO_KR_API_KEY`: 공공데이터포털 **Decoding** 키 (백엔드와 같은 값). 캐시된 원본만 쓸 때는 없어도 된다

### 사용법

저장소 루트에서 실행한다.

```bash
# 본문용 요약 (표준 출력)
python scripts/analysis/key_collision.py --sgg 11500,11680,11710,11350 --months 202607,202608,202609

# 부록용 전체 그룹 원본 비교를 파일로
python scripts/analysis/key_collision.py --sgg 11500,11680,11710,11350 --months 202607,202608,202609 \
  --mode full --out docs/analysis/2026-10-key-collision-full.md
```

| 옵션 | 설명 |
|---|---|
| `--sgg` | 시군구 코드(LAWD_CD 5자리), 쉼표로 구분 |
| `--months` | 계약월(DEAL_YMD, YYYYMM), 쉼표로 구분 |
| `--raw-dir` | 원본 XML 캐시 디렉터리 (기본 `data/raw`) |
| `--snapshot-dir` | 새로 받은 원본을 조회일별로 남길 디렉터리 (기본 `data/raw-snapshots`) |
| `--mode` | `summary`(기본): 구×월 요약, 유형별 집계, 그룹 목록, 대표 사례 원본 비교 / `full`: 모든 그룹의 원본 비교 |
| `--out` | 출력 파일. 없으면 표준 출력 |

### 원본 캐시

- API 응답은 `{raw-dir}/{sgg}_{yyyymm}_p{page}.xml`로 저장되고, 파일이 있으면 API를 다시 호출하지 않는다. 같은 원본으로 몇 번을 돌려도 결과가 같다
- 조회 시각은 `{raw-dir}/fetched_at.json`에 기록되고, `full` 출력 상단에 표시된다
- API로 새로 받은 페이지는 `{snapshot-dir}/{조회일 YYYY-MM-DD}/{sgg}_{yyyymm}_p{page}.xml`에도 저장된다. 캐시를 지우고 다시 받아도 이전 조회본이 남아서, 날짜별로 값이 어떻게 채워지는지 비교할 수 있다 (2026-10-25 이전 조회본은 `2026-10-22/11500_202609_p1.xml` 하나만 있음)
- 최신 데이터로 다시 보려면 해당 XML 파일을 지우고 실행한다. 신고·해제·등기가 계속 반영되므로 조회 시점에 따라 결과가 달라진다
- `data/`는 `.gitignore`에 들어 있어 커밋되지 않는다

### 분류 기준

필드를 두 종류로 나눈다.

- **가변 필드**: `cdealType`, `cdealDay`, `rgstDate`, `aptDong` (나중에 채워지거나 바뀌는 값)
- **불변 필드**: 그 외 전부

키가 같은 레코드가 2건 이상인 그룹을 불변 필드 기준으로 분류한다 (해제 = `cdealType` 값 있음).

| 유형 | 조건 |
|---|---|
| (a) 해제 후 재신고 후보 | 정상 1건 + 해제 1건 이상, 불변 필드 동일 |
| (b) 모두 정상 | 해제 0건, 불변 필드 동일 |
| (b′) 일부만 해제 | 정상 2건 이상 + 해제 1건 이상, 불변 필드 동일 |
| (c) 기타 | 불변 필드가 다름 (다른 필드 이름 기록), 또는 전부 해제 |

- 면적은 수집 코드(`TradeXmlParser`)와 같이 소수 둘째 자리 HALF_UP으로 반올림해서 비교한다
- 병합 건수는 Σ(그룹 크기 − 1)로, 현재 수집 코드(`TradeUpsertService.mergeSameKey`)가 합치는 건수와 같다
- 그룹마다 레코드끼리 값이 다른 가변 필드를 따로 표시하고, (b)는 `aptDong`으로 레코드를 구분할 수 있는지 `rgstDate`와 함께 집계한다
- 정황 판단(일괄 매매인지 등)은 하지 않는다. 판단 근거로 같은 단지·같은 날 거래 수, 매도/매수 구분, 거래 유형을 함께 출력한다
