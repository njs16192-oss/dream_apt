# Troubleshooting

## Docker daemon 연결 실패
- 증상: `failed to connect to the docker API at npipe:////./pipe/docker_engine`
- 원인: Docker Desktop 미실행
- 해결: Docker Desktop 실행 후 Engine running 확인

## npm run dev - package.json 없음
- 증상: `ENOENT: no such file or directory, open '...\package.json'`
- 원인: 프로젝트 루트에서 실행함
- 해결: `frontend` 폴더로 이동 후 실행
## 수집 Job이 STARTED로 남아 있음
- 증상: 수집 배치를 강제 종료(OOM, `kill -9`, CronJob 시간 초과 등)한 뒤 `BATCH_JOB_EXECUTION.STATUS`가 `STARTED`로 남아 있음. 시작 후 2시간이 안 됐으면 같은 모드 실행이 거절됨 (관리 API 409, 명령줄은 종료 코드 4)
- 원인: Spring Batch가 상태를 `FAILED`로 바꿀 기회 없이 프로세스가 끝남 (ADR 006 3-⑦)
- 해결:
  1. 그 실행이 정말 죽었는지 확인 (로컬: `java` 프로세스, k8s: Pod 상태). 시작 후 `COLLECT_STALE_RUNNING_AFTER`(기본 2시간)가 지나면 새 실행은 자동으로 시작되고 WARN 로그만 남음
  2. 기록 정리: `POST /api/admin/collect/jobs/{id}/recover` → `JobOperator.recover`가 job·step을 `FAILED`로 바꿈. 아직 2시간이 안 된 실행은 409 (살아 있는 실행을 실수로 정리하지 않도록)
  3. 실행 id 찾기: `SELECT job_execution_id, start_time FROM batch_job_execution WHERE status IN ('STARTING', 'STARTED');`

## 지오코딩에서 API_ERROR가 대량으로 기록됨
- 증상: `POST /api/admin/geocode` 결과의 `apiError`가 크다(2026-10-30, 1,000개 중 618개). 서버 로그 경고가 `errorCode=E0007`("짧은 시간에 다량의 주소검색 요청이 있습니다")
- 원인: 도로명주소 API의 빈도 제한. 호출 간격 100ms(초당 약 10회)가 한도보다 빨랐다. 처음에는 E0007을 단지별 오류로 분류해서 API_ERROR로 기록했다 (ADR 010 ④·⑥)
- 해결:
  1. E0007은 일시 오류로 분류한다(기록하지 않음). 호출 간격 기본값은 300ms다. 다시 나면 `JUSO_REQUEST_INTERVAL`을 올린다
  2. 잘못 기록된 행 되돌리기. 먼저 로그의 경고가 모두 E0007인지 확인한 뒤 실행한다:
     `UPDATE apt_complex SET geocode_attempted_at = NULL, geocode_error = NULL WHERE geocode_error = 'API_ERROR';`
  3. `remaining`이 0이 될 때까지 다시 호출한다
