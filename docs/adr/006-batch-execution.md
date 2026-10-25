# 006. 수집 배치 실행: Spring Batch Job을 별도 프로세스로 실행

- 상태: 초안
- 날짜: 2026-10-25
- 관련: [ADR 004](004-trade-reconcile.md) (구×월 대조, advisory lock), [ADR 005](005-collect-schedule.md) (수집 주기)

## 결정 요약

| 항목 | 결정 |
|---|---|
| 실행 방식 | Spring Batch Job `collectTradeJob` 하나. 모드(`daily`/`weekly`/`backfill`)와 기준일은 Job 파라미터 |
| 실행 위치 | 웹 앱과 같은 이미지를 `batch` 프로필로 실행. 전용 runner가 Job 하나를 실행하고 종료 코드와 함께 끝난다 → k8s CronJob (8단계) |
| 웹 앱 | Job 자동 실행 안 함 (`spring.batch.job.enabled=false`). 관리 API로만 수동 실행 |
| Job 구조 | Tasklet step 1개. 구×월을 하나씩 처리하고, 하나의 실패는 기록하고 다음으로 넘어감 |
| 이어하기 | Spring Batch restart 대신 `collect_history`(구×월별 최신 상태)를 기준으로 |
| JobInstance | 실행마다 고유한 `run.id`(시작 시각 ms)를 넣어 새 인스턴스. incrementer는 쓰지 않음 |
| 메타데이터 | `BATCH_*` 테이블을 Flyway 마이그레이션으로 관리 (`initialize-schema=never`) |
| 종료 코드 | 0 성공 / 1 시작 실패 / 2 Job 실패 / 3 일부 구×월 실패 / 4 다른 실행 진행 중 |
| 동시 실행 | **같은 모드끼리만** 막음: 관리 API 409, Job 시작 리스너, CronJob `concurrencyPolicy: Forbid`. 시작 후 2시간 지난 `STARTED`는 실행 중으로 보지 않음 |
| 시간대 | 기준일은 `Clock`(Asia/Seoul). DB 시각 컬럼은 `TIMESTAMPTZ`로 통일 |

---

## 1. 배경

ADR 005에 따라 수집은 하루 150회, 주 150회, 초기 적재 때 약 2,050회를 호출한다. 호출 간격을 두면 한 번 실행에 수 분 – 수십 분이 걸린다.
이 작업에 필요한 것은 다음과 같다.

- **웹 서버와 분리**: 웹 요청 처리와 자원(스레드, DB 커넥션, 메모리)을 나눠 쓰지 않는다. 배포·재시작이 서로 영향을 주지 않는다
- **한 번만 실행**: 웹 앱 레플리카가 여러 개여도 수집은 한 번만 돈다
- **실행 이력**: 언제, 어떤 모드로, 얼마나 걸려, 어떤 결과로 끝났는지 남는다
- **실패 처리**: 구×월 하나가 실패해도 나머지는 계속한다. 실패는 기록하고 다음 실행에서 다시 시도한다
- **운영**: 실행 일정과 실패 감지를 k8s(8단계)에 맡길 수 있다

## 2. 검토한 대안

### A. Spring Batch Job + 별도 프로세스(k8s CronJob) — 선택

웹 앱과 같은 코드·이미지를 `batch` 프로필(웹 서버 없음)로 띄워 Job 하나를 실행하고 종료한다. 일정은 k8s CronJob이 정한다.

- 장점
  - 웹 서버와 프로세스가 분리된다. 웹 앱은 레플리카 수와 관계없이 수집을 실행하지 않는다
  - 실행 이력(`BATCH_JOB_EXECUTION`, `BATCH_STEP_EXECUTION`)이 시작·종료 시각, 상태, 종료 코드, 처리 건수와 함께 자동으로 남는다
  - 종료 코드로 성공·실패를 k8s에 알릴 수 있다. 실패 알림·재실행 정책은 CronJob 설정으로 정한다
  - Job 파라미터로 모드·기준일을 넘겨 과거 날짜 기준으로 다시 돌리기 쉽다
  - 실무에서 많이 쓰는 배치 프레임워크라 학습 가치가 있다
- 단점
  - 메타데이터 테이블 6개와 시퀀스 3개가 생기고, Batch 버전을 올릴 때 스키마 변경을 직접 마이그레이션해야 한다
  - Job/Step/Tasklet, JobInstance/JobExecution, 파라미터 같은 개념을 익혀야 한다
  - JVM을 매번 새로 띄우므로 실행마다 기동 시간(수 초)이 든다

### B. `@Scheduled` + ShedLock (웹 앱 안에서 실행) — 기각

웹 앱 안에서 `@Scheduled(cron = ...)`으로 실행하고, 레플리카가 여러 개일 때 ShedLock(DB 테이블 락)으로 한 인스턴스만 실행하게 한다.

- 장점: 의존성 하나와 테이블 하나로 끝난다. 별도 배포 단위가 없다. 개념이 단순하다
- 단점
  - **웹 서버 안에서 돈다.** 수십 분짜리 수집이 웹 앱의 스레드·커넥션 풀·메모리를 함께 쓴다. 수집 중에 웹 앱을 배포하면 수집이 중간에 끊긴다
  - 실행 이력이 없다. ShedLock 테이블은 "마지막으로 락을 잡은 시각"만 남긴다. 결과·소요 시간·실패 여부는 직접 테이블을 만들어 기록해야 한다
  - 실패를 알리는 표준 경로가 없다. 로그나 별도 지표를 만들어야 한다
  - 일정이 코드에 들어간다. 바꾸려면 다시 배포해야 한다 (설정값으로 빼도 재시작은 필요)
  - 과거 기준일로 다시 돌리는 것 같은 수동 실행은 별도 API를 만들어야 한다

### C. Spring Batch를 웹 앱 안에서 `@Scheduled`로 실행 — 기각

Spring Batch의 이력 관리는 얻지만, B의 "웹 서버 안에서 돈다"는 문제가 그대로다. 중복 실행 방지도 따로 필요하다.

### 비교

| | A. Batch + CronJob | B. @Scheduled + ShedLock | C. Batch in 웹 앱 |
|---|---|---|---|
| 웹 서버와 분리 | O | X | X |
| 중복 실행 방지 | CronJob `Forbid` + 시작 리스너 | ShedLock | 별도 필요 |
| 실행 이력 | `BATCH_*` 자동 | 직접 구현 | `BATCH_*` 자동 |
| 실패 감지 | 종료 코드 → k8s | 직접 구현 | 직접 구현 |
| 일정 변경 | 매니페스트 수정 | 재배포·재시작 | 재배포·재시작 |
| 복잡도 | 높음 | 낮음 | 중간 |

수집은 이 서비스의 데이터 원천이고, 실행 이력·실패 감지가 운영의 핵심이라 복잡도를 감수하고 A를 선택한다.

## 3. 설계

### ① Job 구조: Tasklet step 1개
- 대상 구×월 목록 = `region`(수집 대상 구) × 모드별 월 범위 (ADR 005). `backfill`이면 `collect_history`에서 이미 성공한 구×월을 뺀다
- Tasklet이 대상을 하나씩 처리한다: 호출 상한 확인(`backfill`만) → 수집(`TradeCollectService`) → 결과를 `collect_history`에 기록
- **chunk 방식을 쓰지 않은 이유**: chunk 방식에서는 read-process-write 전체가 한 트랜잭션이라 API 호출 중에도 DB 커넥션을 잡는다. 지금 수집 코드는 "API 호출은 트랜잭션 밖, 저장만 트랜잭션"(`TradeCollectService`)으로 설계돼 있다. 그래서 Tasklet step은 `ResourcelessTransactionManager`(아무것도 하지 않는 트랜잭션 매니저)로 감싸고, 저장 트랜잭션은 기존처럼 `TradeReconcileService`가 연다
- 처리 건수 요약(성공·실패·건너뜀·남은 대상·호출 수)은 StepExecution의 ExecutionContext와 로그에 남긴다

### ② 실패 격리와 재시도
| 오류 | 예 | 처리 |
|---|---|---|
| 일시 오류 | 연결·읽기 타임아웃, 5xx | 지수 백오프로 재시도 (기본 3회, 2초부터 2배). 끝내 실패하면 그 구×월만 실패 |
| 구×월 오류 | 원본 불완전(건수 불일치), 범위 밖 레코드, 파싱 오류 | 재시도 없이 그 구×월만 실패로 기록하고 다음으로 |
| 실행 중단 오류 | 일일 한도 초과, 인증 실패(키 없음·미등록) | 기록하고 **즉시 중단**. 남은 구×월도 모두 같은 이유로 실패하므로 호출만 낭비된다 |

- API 호출 사이에는 최소 간격(기본 300ms)을 둔다. 한 구×월의 여러 페이지 사이도 포함한다
- 재시도는 Spring Framework 7 내장 `RetryTemplate`을 쓴다 (spring-retry 의존성 없이)

### ③ 이어하기: `collect_history`
- Spring Batch의 restart는 "실패한 JobExecution을 같은 파라미터로 다시 실행하면 멈춘 지점부터"다. 이 Job에서는 맞지 않는다
  - 구×월 실패는 Job 실패가 아니다. Job은 끝까지 돌고 끝난다
  - 이어할 단위는 step이 아니라 구×월이다. 그리고 "이미 성공했는가"는 Job 실행과 무관하게 데이터의 상태다 (관리 API로 수동 수집한 구×월도 포함)
- 그래서 구×월별 최신 상태를 `collect_history`(PK `sgg_cd, deal_ym`)에 기록하고, `backfill`은 성공 기록이 없는 구×월만 처리한다
- `collect_history`는 실행별 로그가 아니라 구×월당 1행이다. 실행별 이력은 `BATCH_*`가 맡는다. 실패하면 상태·사유·시도 시각만 바꾸고, 마지막 성공 시각과 결과 건수는 유지한다 (화면의 "데이터 기준일"에 쓴다)
- Spring Batch는 같은 식별 파라미터로 이미 완료된 JobInstance를 다시 실행하지 못하게 한다. 같은 날 `daily`를 두 번 돌리거나 `backfill`을 여러 번 이어 돌려야 하므로, 실행마다 고유한 `run.id`(시작 시각 ms)를 넣어 새 JobInstance로 만든다
  - `RunIdIncrementer`를 쓰지 않은 이유: Spring Batch 6.0.5의 `JobOperator.start(job, parameters)`는 Job에 incrementer가 있으면 넘긴 파라미터를 버리고(경고 로그만 남김) `startNextInstance`를 실행한다. 그러면 `mode`·`baseDate`가 사라진다
  - `baseDate`를 생략해도 실행할 때 서울 기준 오늘로 채워서 파라미터에 남긴다. 나중에 어떤 기준일로 돌았는지 메타데이터에서 바로 보인다

### ④ 메타데이터 테이블은 Flyway로
- 스키마 변경은 Flyway로만 한다는 프로젝트 규칙을 따른다. Spring Batch가 jar에 넣어 제공하는 `schema-postgresql.sql`을 마이그레이션 파일로 복사하고, 자동 초기화는 끈다 (`spring.batch.jdbc.initialize-schema=never`)
- 대가: Spring Batch 버전을 올릴 때 스키마가 바뀌었는지 확인하고, 바뀌었으면 마이그레이션을 직접 추가해야 한다. 마이그레이션 파일에 원본 버전을 적어 둔다

### ⑤ 실행 경로
| 경로 | 방법 |
|---|---|
| 로컬 명령 | `./gradlew bootRun --args='--spring.profiles.active=batch --mode=daily [--baseDate=2026-10-25]'` (종료 코드를 보려면 `java -jar`로 실행. Gradle을 거치면 Gradle의 종료 코드가 된다) |
| 관리 API | `POST /api/admin/collect/jobs?mode=daily[&baseDate=...]` → 202 + 실행 id (비동기), `GET /api/admin/collect/jobs/{id}`로 상태·요약 조회, `POST /api/admin/collect/jobs/{id}/recover`로 오래된 `STARTED` 기록 정리 |
| 운영 (8단계) | k8s CronJob이 같은 이미지를 `batch` 프로필로 실행 |

- 웹 앱은 Job을 자동 실행하지 않는다 (`spring.batch.job.enabled=false`). `batch` 프로필만 웹 서버 없이 기동해서 Job을 실행하고 종료한다
- `batch` 프로필에서도 Boot의 자동 실행(`spring.batch.job.enabled=true`)은 쓰지 않고 전용 runner(`CollectJobCommandLineRunner`)가 실행한다. Boot의 runner는 인자를 그대로 파라미터로 넘겨서 `run.id`를 넣을 수 없고, 종료 코드도 BatchStatus 순번이라 일부 실패를 구분하지 못한다
- 관리 API의 비동기 실행은 별도 스레드에서 돈다. 모드가 3개라 동시에 최대 3개까지 돈다 (같은 모드는 ⑦에서 막는다)
- 관리 API는 인증이 없으므로 외부 배포 전에 보호 장치가 필요하다 (기존 수동 수집 API와 같음)

### ⑥ 종료 코드
CronJob은 컨테이너 종료 코드가 0이 아니면 실패로 본다. 종료 코드를 원인별로 나눠 로그·알림에서 바로 구분되게 한다.

| 종료 코드 | 상황 | Job 상태 (BatchStatus / ExitStatus) |
|---:|---|---|
| 0 | 모든 대상 구×월 성공 (`backfill`이 호출 상한에 걸려 멈춘 경우도 포함) | `COMPLETED` / `COMPLETED` |
| 1 | 기동 실패, 잘못된 Job 파라미터 (Spring Boot 기본) | – |
| 2 | Job 실패: 일일 한도 초과, 인증 실패, 예상하지 못한 오류 | `FAILED` / `FAILED` |
| 3 | 일부 구×월 실패. 나머지는 끝까지 처리함 | `COMPLETED` / `COMPLETED_WITH_FAILURES` |
| 4 | 같은 모드의 다른 실행이 진행 중이라 시작하지 않음 | `STOPPED` / `ALREADY_RUNNING` |

- **일부 실패(3)도 0이 아니게 한다.** 0으로 끝내면 CronJob 기록상 성공이라 특정 구×월이 계속 실패해도 알아채기 어렵다
- **자동 재실행은 하지 않는다.** 8단계에서 CronJob을 `backoffLimit: 0`으로 둔다. 실패한 구×월은 다음 일일 수집(또는 다음 `backfill`)에서 어차피 다시 시도된다. 바로 재실행하면 성공한 구×월까지 다시 받아 호출 한도만 쓴다. 특히 한도 초과(2)는 재실행해도 다시 실패한다
- `backfill`이 호출 상한에 걸려 멈추는 것은 의도한 동작이라 0이다. 남은 대상 수는 실행 요약에 남는다
- 중복 실행(4)은 `STOPPED`로 남긴다. `COMPLETED`로 두면 메타데이터에서 성공한 실행으로 보여, "최근 성공 실행" 같은 모니터링 지표가 아무것도 수집하지 않은 실행으로 오염된다
- Spring Boot 기본 종료 코드 생성기는 BatchStatus 순번을 쓰므로(`COMPLETED`면 0) 일부 실패를 구분하지 못한다. 전용 runner가 ExitStatus를 보고 위 표대로 종료 코드를 정한다 (`CollectJobExitCodes`)

### ⑦ 동시 실행 방지: 같은 모드끼리만
**같은 모드**(`mode` 파라미터)의 실행은 한 번에 하나만 돈다. 모드가 다르면 동시에 돌아도 된다.

- 같은 모드 두 실행은 같은 대상을 겹쳐 받아 호출 수만 두 배가 된다. `backfill` 둘은 성공 기록을 서로 보지 못한 채 같은 구×월을 나눠 받지 못하고 겹친다
- 다른 모드는 대상이 거의 겹치지 않는다. 일일(M ~ M-5)과 주간(M-6 ~ M-11)은 대상 월이 겹치지 않고, `backfill`은 이미 성공한 구×월을 건너뛴다. 겹쳐도 같은 구×월을 동시에 쓰는 것은 ADR 004의 advisory lock이 막아서 데이터는 안전하다
- 모드와 관계없이 막으면, `backfill`이 길게 돌 때 일요일 주간 수집이 거절된다. 주간 수집은 다음 기회가 일주일 뒤라 그만큼 반영이 늦어진다

| 겹치는 경우 | 막는 곳 |
|---|---|
| 관리 API로 실행 요청 | 같은 모드로 실행 중인 `collectTradeJob`이 있으면 **409 Conflict** |
| 같은 CronJob끼리 (일일 실행이 길어져 다음 일정과 겹침) | 8단계에서 CronJob `concurrencyPolicy: Forbid` |
| CronJob과 관리 API 실행이 겹침 | Job 시작 리스너가 `BATCH_JOB_EXECUTION`에서 같은 `mode` 파라미터로 실행 중(`STARTING`/`STARTED`)인 다른 실행을 찾으면 시작하지 않고 끝냄 (`STOPPED`, 종료 코드 4) |

- 리스너 확인과 실행 시작 사이에 틈이 있어 완전한 상호 배제는 아니다. 일정상 거의 겹치지 않고, 겹쳐도 advisory lock 덕분에 데이터는 안전하므로 받아들인다

**오래된 `STARTED` 기록**
- 프로세스가 강제 종료되면(OOM, 노드 장애, `kill -9`, CronJob 시간 초과) Spring Batch는 실행 기록을 `FAILED`로 바꿀 기회가 없어 `STARTED`로 남는다. 그대로 두면 리스너와 관리 API가 그 모드를 **영원히** 거절한다
- 그래서 시작 후 일정 시간(`COLLECT_STALE_RUNNING_AFTER`, 기본 2시간)이 지난 `STARTED`/`STARTING` 기록은 실행 중으로 보지 않는다. 대신 실행 id와 시작 시각을 WARN 로그로 남긴다. 기록 자체는 고치지 않는다 (판단만 하고, 정리는 사람이 한다)
- 2시간 근거: 가장 긴 실행은 `backfill` 1회(최대 2,000회 호출 × (간격 300ms + 응답 시간), 응답이 1초 안팎이면 약 45분)다. 재시도 백오프와 느린 응답을 고려해 정상 실행이 넘지 않을 만큼 여유를 둔다. 실측 소요 시간이 길어지면 이 값과 호출 상한을 함께 조정한다
- 8단계 CronJob의 `activeDeadlineSeconds`는 **이 값보다 짧게** 잡는다 (예: 1.5시간). 그래야 k8s가 먼저 시간 초과로 Pod를 끝내고, 그 뒤에 남은 `STARTED` 기록만 오래된 것으로 취급된다. 반대로 잡으면 아직 돌고 있는 실행을 오래된 것으로 보고 같은 모드를 하나 더 띄울 수 있다
- 남은 기록은 Spring Batch 6의 `JobOperator.recover(JobExecution)`로 정리한다 (6.0.5에서 확인). 실행 중인 step과 Job을 `FAILED`로 바꾸고 종료 시각을 기록하며, ExecutionContext에 `batch.recovered` 표시를 남긴다. 단, **그 실행이 실제로 죽었는지는 확인하지 않는다.** 살아 있는 실행에 호출하면 기록만 `FAILED`가 되고 프로세스는 계속 돈다
  - 그래서 자동으로 호출하지 않고 관리 API `POST /api/admin/collect/jobs/{id}/recover`로 사람이 호출한다. 이 API도 오래된 기록(위 기준 시간 초과)만 허용하고, 아니면 409를 준다
  - 절차는 [troubleshooting](../troubleshooting.md)에 적는다

### ⑧ 시간대
- k8s 노드·컨테이너 기본 시간대는 UTC다. 서울 06:00 실행은 UTC로 전날 21:00이라, JVM 기본 시간대로 "오늘"을 구하면 월초에 **이번 달을 지난달로** 잘못 계산한다
- 업무 기준일은 `Clock` 빈(기본 `Asia/Seoul`, `COLLECT_ZONE`으로 변경 가능)으로 구한다. `baseDate` 파라미터가 없으면 이 Clock의 오늘이다
- **DB 시각 컬럼은 `TIMESTAMPTZ`로 통일한다.** 기존 `apt_complex`·`apt_trade`의 `TIMESTAMP` 컬럼은 세션 시간대 기준 현지 시각을 시간대 정보 없이 저장한다. pgjdbc는 세션 시간대를 JVM 시간대로 잡으므로, 개발 PC(KST)와 k8s(UTC)에서 쓴 값이 같은 컬럼에 섞인다. 기존 값은 개발 PC에서 쓴 서울 시각이라 `AT TIME ZONE 'Asia/Seoul'`로 해석해 변환한다 (2026-10-25 확인: DB 기본 시간대는 `Etc/UTC`지만 마지막 `created_at` 02:49가 수집 커밋 시각 02:49 +0900과 일치)
- `BATCH_*` 메타데이터는 Spring Batch가 제공하는 스키마(`TIMESTAMP`)를 그대로 쓴다. 고치면 버전업 때마다 바꿔야 한다. 이 값은 JVM 시간대로 기록되므로 8단계에서 컨테이너에 `TZ=Asia/Seoul`을 지정한다. CronJob 일정도 `timeZone: Asia/Seoul`로 적는다

## 4. 결과

- 수집은 웹 앱과 독립적으로 실행·실패·재시작된다
- 실행 이력은 `BATCH_*`, 구×월별 데이터 상태는 `collect_history`에서 본다
- CronJob은 종료 코드로 성공(0)·일부 실패(3)·실패(2)·같은 모드 중복(4)을 구분할 수 있다
- 대가: Spring Batch 개념과 메타데이터 스키마를 관리해야 한다. 배치 전용 실행 경로(프로필, 종료 코드)가 생긴다
- 실행 요약(`summary.*`)은 step의 ExecutionContext에 있는데, Spring Batch 기본 직렬화(Java 직렬화 + Base64)라 SQL로는 읽을 수 없다. 지금은 관리 API 조회로 본다. SQL·대시보드에서 직접 봐야 하면 JSON 직렬화(Jackson)로 바꾸는 것을 검토한다
