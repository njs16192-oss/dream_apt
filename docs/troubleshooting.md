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
