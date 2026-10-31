# Dream APT

아파트 실거래가 트래커 & 갈아타기 시뮬레이터 웹 서비스. 개인 포트폴리오 프로젝트.

## 기술 스택
- Backend: Java 17, Spring Boot, Gradle (Groovy), Spring Data JPA
- Frontend: React + TypeScript (Vite)
- DB: PostgreSQL 17 + PostGIS 3.5 (로컬은 Docker)
- 예정: Spring Batch, Redis, GitHub Actions, k3s, ArgoCD

## 폴더 구조
```
backend/    Spring Boot (base package: com.dreamapt)
frontend/   React + Vite
infra/      docker-compose.yml, k8s 매니페스트
docs/adr/   기술 결정 기록 (ADR)
docs/troubleshooting.md
```

## 로컬 실행
- DB: `docker compose -f infra/docker-compose.yml up -d` (db/user/pw 모두 dreamapt, 5432)
- Backend: `cd backend && ./gradlew bootRun` (8080). 활성 프로필을 안 주면 `local` 프로필 (관리 API 문서 켜짐)
  - API 문서: `http://localhost:8080/swagger-ui.html` (public 그룹은 운영에서도 공개, admin 그룹은 `API_DOCS_ADMIN_ENABLED=true`일 때만)
- 수집 배치 (docs/adr/006-batch-execution.md): `cd backend && ./gradlew bootRun --args='--spring.profiles.active=batch --mode=daily'`
  - mode: daily / weekly / backfill, 선택 `--baseDate=YYYY-MM-DD` (기본: 서울 기준 오늘)
  - 웹 앱에서는 Job이 자동 실행되지 않음. 관리 API `POST /api/admin/collect/jobs?mode=daily`로도 실행 가능
- 단지 좌표 지오코딩 (docs/adr/010-geocoding.md): 관리 API `POST /api/admin/geocode?limit=1000` (`retryFailed=true`면 실패한 단지도 다시)
  - 행안부 도로명주소 API 키 `JUSO_SEARCH_API_KEY`, `JUSO_COORD_API_KEY` 필요
  - 한 번에 다 돌리면 HTTP 타임아웃에 걸릴 수 있어서, 응답의 `remaining`이 0이 될 때까지 나눠 호출
- Frontend: `cd frontend && npm run dev` (5173, `/api`는 8080으로 프록시)
  - 테스트: `npm test` (Vitest)
  - API 타입 재생성: 백엔드를 띄운 상태에서 `npm run gen:api` → `src/api/schema.d.ts` (커밋 대상, docs/adr/008-frontend-structure.md). 백엔드 응답·경로를 바꾸면 같은 PR에서 재생성
- CI: GitHub Actions (`.github/workflows/ci.yml`, docs/adr/009-ci.md). PR마다 `./gradlew test`, 프론트 lint·test·build, `schema.d.ts` 최신 여부
- 개발 환경은 Windows + VS Code

## 데이터 설계 (반드시 docs/adr/002-trade-data-schema.md 참고)
- 데이터 출처: 공공데이터포털 「국토교통부_아파트 매매 실거래가 상세 자료」
  - `https://apis.data.go.kr/1613000/RTMSDataSvcAptTradeDev/getRTMSDataSvcAptTradeDev`
  - 파라미터: serviceKey, LAWD_CD(시군구 5자리), DEAL_YMD(YYYYMM), pageNo, numOfRows
  - 응답 샘플: `backend/src/test/resources/sample/apt-trade-sample.xml`
- 테이블: `apt_complex`(단지, PK=aptSeq), `apt_trade`(거래)
- 거래 식별: 구×월 단위 대조(reconcile) (docs/adr/004-trade-reconcile.md)
  - 같은 키 (apt_seq, deal_date, exclu_use_ar, floor, deal_amount)의 행이 여러 개일 수 있음 (unique 제약 없음)
  - 그룹 안에서 정확 일치 → unchanged, "빈 값 → 채워짐" → update, 남은 응답 → insert, 남은 행 → `missing_since` 표시 (삭제 안 함)
  - 나중에 채워지는 값(apt_dong, registered_date, cancelled/cancel_date)은 갱신 대상
- 거래금액 `deal_amount`는 **만원 단위 INTEGER**
- API의 빈 값은 공백 문자열(" ")로 옴 → trim 후 빈 문자열은 null
- 해제 거래(`cdealType` 값 있음)는 `cancelled=true`로 저장, 삭제하지 않음
- **유효 거래 = `cancelled = false AND missing_since IS NULL`**. 가격 추이, 신고가 판단 등 모든 조회는 이 조건을 기본으로 한다 (구현 방식(뷰, 공통 조건 등)은 3단계에서 결정)
- 면적은 전용면적(㎡)만 제공됨

## 규칙
- Java 17 문법까지만 사용
- 스키마 변경은 Flyway 마이그레이션 파일로만 (`ddl-auto`는 validate)
- API 키 등 비밀값은 커밋 금지. 환경변수 `DATA_GO_KR_API_KEY`, `JUSO_SEARCH_API_KEY`, `JUSO_COORD_API_KEY`로 주입
- 설정값은 `${ENV_NAME:기본값}` 형식 사용 (k8s에서 환경변수로 교체 예정)
- 커밋 메시지: Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`)
  - 타입 접두사는 영어, 설명과 본문은 한국어 (예: `feat: 실거래가 수집 API 추가`, `fix: 빈 동 정보를 null로 저장`)
  - PR 제목도 같은 형식
- 브랜치: 작업은 `main`에서 분기한 브랜치에서 진행하고 PR로 머지. `main`에 직접 커밋·push 하지 않음
  - 브랜치명: `<type>/<짧은-설명>` (예: `feat/trade-collector`, `fix/null-apt-dong`, `docs/adr-003`)

## 작업 방식
- 구현 전에 먼저 계획을 제시하고 확인받은 뒤 진행
- 작은 단위로 나눠서 작업하고, 단위마다 무엇을 왜 했는지 짧게 설명
- 새로운 기술 결정이 생기면 `docs/adr/`에 ADR 초안 작성을 제안
- 이 프로젝트는 학습 목적도 있으므로, 익숙하지 않을 수 있는 개념은 간단히 설명
