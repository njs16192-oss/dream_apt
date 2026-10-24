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
- Backend: `cd backend && ./gradlew bootRun` (8080)
- Frontend: `cd frontend && npm run dev` (5173, `/api`는 8080으로 프록시)
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
- API 키 등 비밀값은 커밋 금지. 환경변수 `DATA_GO_KR_API_KEY`로 주입
- 설정값은 `${ENV_NAME:기본값}` 형식 사용 (k8s에서 환경변수로 교체 예정)
- 커밋 메시지: Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`)
- 브랜치: 작업은 `main`에서 분기한 브랜치에서 진행하고 PR로 머지. `main`에 직접 커밋·push 하지 않음
  - 브랜치명: `<type>/<짧은-설명>` (예: `feat/trade-collector`, `fix/null-apt-dong`, `docs/adr-003`)

## 작업 방식
- 구현 전에 먼저 계획을 제시하고 확인받은 뒤 진행
- 작은 단위로 나눠서 작업하고, 단위마다 무엇을 왜 했는지 짧게 설명
- 새로운 기술 결정이 생기면 `docs/adr/`에 ADR 초안 작성을 제안
- 이 프로젝트는 학습 목적도 있으므로, 익숙하지 않을 수 있는 개념은 간단히 설명
