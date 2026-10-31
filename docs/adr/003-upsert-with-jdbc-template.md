# 003. 수집 데이터 저장에 JdbcTemplate batch + ON CONFLICT 사용

- 상태: 확정 (거래의 중복 처리·건수 계산·같은 키 병합은 [ADR 004](004-trade-reconcile.md)로 대체. JdbcTemplate batch와 단지 upsert는 유지)
- 날짜: 2026-10-22
- 관련: [ADR 002](002-trade-data-schema.md) (unique 키 + upsert 결정), [키 충돌 분석](../analysis/2026-10-key-collision.md)

## 결정 요약

| 항목 | 결정 |
|---|---|
| 저장 방식 | JPA `saveAll` 대신 `NamedParameterJdbcTemplate.batchUpdate` |
| 중복 처리 | PostgreSQL `INSERT ... ON CONFLICT DO UPDATE ... WHERE ... IS DISTINCT FROM` (거래는 ADR 004 대조로 대체, 단지는 유지) |
| 신규/갱신 건수 | upsert 전후 행 수 차이로 계산 (ADR 004에서 대조 결과로 대체) |
| 배치 안 같은 키 | Java에서 정상 거래 우선 규칙으로 하나로 합침 (**임시 조치**, ADR 004에서 대체됨) |
| 조회 | 기존처럼 JPA 엔티티 사용 (저장 경로만 JDBC) |

---

## 1. 배경

수집 1회는 "한 시군구 · 한 달" 단위로 수백 건이다. 같은 달을 여러 번 수집하므로 대부분은 이미 있는 거래이고, 그중 일부만 해제·등기일·동 값이 바뀐다 (ADR 002).
즉 저장 로직은 **"없으면 INSERT, 있으면 바뀐 값만 UPDATE"를 대량으로** 해야 한다.

## 2. JPA `saveAll`로 하면 생기는 문제

### ① 존재 여부를 확인하려면 레코드마다 SELECT가 필요하다
`apt_trade`의 PK는 `BIGSERIAL`이고 중복 판단은 별도의 unique 키로 한다. `saveAll`은 PK로만 신규/기존을 판단하므로, upsert를 하려면 레코드마다 unique 키로 먼저 조회해야 한다.

### ② IDENTITY 전략에서는 Hibernate가 INSERT를 batch로 묶지 못한다
`@GeneratedValue(strategy = IDENTITY)`는 INSERT를 실행해야 id를 알 수 있어서, Hibernate는 이 엔티티의 INSERT를 한 건씩 즉시 실행한다. `hibernate.jdbc.batch_size`를 설정해도 적용되지 않는다.

### 쿼리 수 비교 (추정)
강서구 2026-09 실데이터 규모(252건 → 단지 108개, 거래 239건) 기준으로 계산한 값이다. 실측값이 아니다.

| | JPA (조회 후 save) | JdbcTemplate batch + ON CONFLICT |
|---|---|---|
| 단지 | 조회 108 + INSERT 108 | upsert 108 (batch 1회) |
| 거래 | 조회 252 + INSERT 239 | upsert 239 (batch 1회) |
| 건수 계산용 | (영속성 컨텍스트로 판단 가능) | count 2 |
| **SQL 실행 수** | **약 700** | **약 350** |
| **DB 왕복** | **약 700** (한 건씩) | **batch 2회 + count 2회** |

- 재수집할 때는 JPA도 INSERT가 UPDATE로 바뀔 뿐 조회가 그대로 필요하다. JdbcTemplate 쪽은 값이 같으면 `WHERE ... IS DISTINCT FROM`에 걸려 실제 쓰기가 일어나지 않는다.
- 참고로 현재 구현에서 API 호출을 포함한 수집 1회가 로컬 기준 0.17~0.57초였다. 지금 규모에서는 성능 자체가 결정적 이유는 아니고, 전국 단위 배치(Spring Batch 예정)로 커질 때 차이가 커진다.

## 3. ON CONFLICT를 선택한 이유

- **원자성**: "조회 → 없으면 INSERT"는 두 문장 사이에 다른 수집이 끼어들면 unique 위반이 난다. `ON CONFLICT`는 한 문장 안에서 DB가 처리한다.
- **키를 DB가 판단**: 중복 기준이 `uk_apt_trade` 제약 하나로 모인다. Java 쪽에 비교 로직을 따로 두지 않는다.
- **바뀐 값만 갱신**: `DO UPDATE ... WHERE (기존 값) IS DISTINCT FROM (새 값)`으로 값이 같으면 UPDATE를 건너뛴다.
  - `updated_at`이 "실제로 값이 바뀐 시각"이라는 의미를 가진다.
  - batch 결과의 영향 행 수가 "신규 + 실제 갱신"이 되어 건수 계산에 쓸 수 있다.
  - `IS DISTINCT FROM`은 NULL끼리를 같다고 본다. `=`를 쓰면 `NULL = NULL`이 NULL이 되어 비교가 틀어진다.
- **갱신 대상 제한**: 키에 없는, 나중에 채워지는 값(`apt_dong`, `cancelled`, `cancel_date`, `registered_date`)만 갱신한다. 단지는 `location`, `created_at`을 건드리지 않는다.
  - 예외 (2026-10-28, [ADR 010](010-geocoding.md) ⑤): 주소(시군구·법정동·지번·도로명)가 바뀌면 `location`과 지오코딩 기록(`geocode_attempted_at`, `geocode_error`)을 NULL로 되돌려 다음 지오코딩 때 새 주소로 다시 받게 한다.

### 단지 정보는 더 늦은 거래일 때만 갱신 (2026-10-31)

- **문제**: 수집은 구×월을 최신 → 과거 순으로 처리한다(일일·주간·backfill 모두). upsert가 마지막에 처리한 달의 단지명·주소·준공연도로 덮어서, backfill 뒤에는 가장 오래된 거래의 주소가 남았다. 일일·주간 수집이 주소를 번갈아 덮을 수도 있다. 주소가 바뀌면 좌표를 지우므로(ADR 010 ⑤) 같은 단지를 계속 다시 지오코딩하게 된다. 첫 일일 수집에서 116개 단지의 주소가 바뀌었고, 54개는 좌표가 43–351m 움직였다
- **규칙**: `source_deal_date`(그 단지 정보를 가져온 거래의 계약일)를 저장하고, 들어온 거래의 계약일이 **더 늦을 때만** 단지 정보 전체(이름·시군구·법정동·지번·도로명·준공연도)를 덮는다
  - 기준을 달이 아니라 계약일로 둔다. 같은 달 안에서도 주소가 다른 거래가 있다(예: 종로 `11110-2212`, 2026-09에 두 주소)
  - 같은 계약일이면 기존 값을 유지한다. 실행 순서와 관계없이 결과가 같다
  - `source_deal_date`가 NULL(V9 이전 행)이면 어떤 거래든 덮는다. 수집이 돌면서 자연히 채워진다
  - **값이 같아도 기준 날짜는 앞당긴다.** 앞당기지 않으면 (A, 5월)에서 9월의 A를 건너뛴 뒤 7월의 B가 덮는다. 그래서 이 upsert는 "바뀐 값만 갱신"(위)의 예외다. 대신 `updated_at`은 단지 정보가 실제로 바뀔 때만 바꿔 "값이 바뀐 시각"의 의미를 지킨다. 단지 저장 건수는 영향 행 수가 아니라 대상 단지 수라 건수 계산에는 영향이 없다
  - 한 구×월 응답 안에서 같은 단지가 여러 번 나오면 계약일이 가장 늦은 거래를 고른다(같으면 먼저 나온 거래). **해제된 거래도 포함한다.** 해제 여부와 관계없이 그 거래에 적힌 주소는 그 시점의 주소다
- **동시 실행**: 일일·주간 수집은 모드가 달라 동시에 돌 수 있고, advisory lock은 구×월 단위라 같은 단지를 동시에 갱신할 수 있다. `ON CONFLICT DO UPDATE ... WHERE`의 날짜 비교는 충돌한 행의 잠금을 잡은 뒤 최신 행 값으로 평가되므로 순서가 꼬이지 않는다
- **기존 데이터 정리 (재수집으로 해소, 2026-10-31 로컬)**: 최근 12개월에 거래가 없는 단지는 일일·주간 수집이 건드리지 않아, backfill이 남긴 값(가장 오래된 거래의 값일 수 있음)이 남았다(2,709개). backfill은 성공한 구×월을 건너뛰므로 다음처럼 다시 수집했다. 운영 DB는 처음부터 새 규칙으로 적재하므로 필요 없다
  1. `UPDATE collect_history SET status = 'FAILED', failure_reason = '재수집: …' WHERE status = 'SUCCESS' AND deal_ym < '202511'` (주간 범위보다 오래된 1,750개 구×월. `last_succeeded_at`은 유지)
  2. backfill 1회(호출 약 1,750회, 9분)
  - 결과: 기준 날짜 NULL 0개. 단지 정보가 실제로 바뀐 단지는 12개였고, 지오코딩 step이 다시 받았다(10 성공, 2 MISMATCH)
  - 거래 대조는 ADR 004대로 같은 결과였다. 30만 8,926건 중 30만 8,900건 그대로, 신규 19·갱신 7·missing 29(늦은 신고·해제)
- 확인 (2026-10-31 로컬): V9 적용 후 일일 수집 2회차와 주간 수집에서 일일이 정한 단지 정보는 바뀌지 않았다(변경 0, 지오코딩 0)

### 신규/갱신 건수 계산
JDBC batch는 INSERT든 UPDATE든 영향 행 수가 1이라 둘을 구분할 수 없다. `RETURNING (xmax = 0)` 방식은 batch에서 결과를 받을 수 없다. 그래서 같은 트랜잭션 안에서 upsert 전후 행 수를 비교한다.

- `inserted = 후 - 전`, `updated = 영향 행 수 - inserted`, `unchanged = 저장 대상 - 영향 행 수`
- 범위는 저장 대상 단지 목록과 계약일 범위로 한정한다.
- 주의: JDBC URL에 `reWriteBatchedInserts=true`를 켜면 영향 행 수가 `-2`(SUCCESS_NO_INFO)로 와서 이 계산이 깨진다.
- 한계: 같은 단지·기간을 동시에 수집하면 수치가 어긋날 수 있다. 저장 결과에는 영향이 없고 관리용 수치라 허용한다.

## 4. 배치 안 같은 키 레코드 병합 (임시 조치)

> **대체됨 (2026-10-25)**: 이 병합 규칙은 제거했다. 같은 키 레코드는 모두 저장하고 구×월 단위로 대조한다 → [ADR 004](004-trade-reconcile.md)

실데이터에서 한 응답 안에 unique 키가 같은 레코드가 252건 중 13건 나왔다 ([키 충돌 분석](../analysis/2026-10-key-collision.md)).
그대로 batch에 넣으면 같은 행을 서로 덮어써서, 응답 순서에 따라 저장 값이 달라지고 재수집할 때마다 `updated`가 발생했다. 정상 거래가 해제로 저장되는 경우도 있었다.

그래서 upsert 전에 Java에서 같은 키를 하나로 합친다 (`TradeUpsertService.mergeSameKey`).

- 우선순위: 정상 거래(`cancelled=false`) → 등기일 있음 → 동 있음 → 값 순
- 값으로만 비교하므로 API 응답 순서와 관계없이 항상 같은 레코드가 남는다.
- 합친 건수는 응답의 `merged`, 그룹별 내역은 WARN 로그로 남긴다.

**이것은 임시 조치다.** 재수집할 때 값이 뒤집히는 문제는 막지만, 서로 다른 정상 거래(분석의 (b) 유형, 이번 표본에서 12건)가 합쳐져 저장되지 않는 문제는 그대로다. 키 재설계는 ADR 004에서 다루고, 그때 이 병합 규칙도 다시 정한다.

## 5. 트레이드오프

- **엔티티와 SQL 이중 관리**: 컬럼이 바뀌면 `AptComplex`/`AptTrade` 엔티티와 `TradeUpsertRepository`의 SQL을 함께 고쳐야 한다. 엔티티만 고치면 `ddl-auto=validate`는 통과하지만 upsert SQL은 런타임에 실패한다.
  - 완화: upsert 통합 테스트(Testcontainers)가 실제 스키마로 SQL을 실행하므로, 둘이 어긋나면 테스트에서 드러난다.
- **영속성 컨텍스트 우회**: JDBC로 쓴 데이터는 같은 트랜잭션의 JPA 1차 캐시에 반영되지 않는다. 저장 트랜잭션 안에서는 JPA로 같은 데이터를 다시 조회하지 않는다.
- **PostgreSQL 종속**: `ON CONFLICT`, `IS DISTINCT FROM`은 PostgreSQL 문법이다. PostGIS를 쓰는 이상 DB를 바꿀 가능성은 낮아 허용한다.
- **엔티티 콜백 미적용**: `@CreationTimestamp`/`@UpdateTimestamp`가 동작하지 않는다. `created_at`은 컬럼 기본값, `updated_at`은 SQL의 `now()`로 채운다.

## 6. 검토한 다른 방법

- **JPA + `@Query(nativeQuery = true)`로 ON CONFLICT**: SQL은 같지만 batch 실행과 영향 행 수를 다루기 불편하다.
- **Spring Batch `JdbcBatchItemWriter`**: 같은 SQL을 그대로 쓸 수 있다. 수집을 Spring Batch로 옮길 때 `TradeUpsertRepository`의 SQL을 재사용한다.
- **매번 삭제 후 재적재**: ADR 002에서 이미 기각했다 (거래 PK가 바뀜).
