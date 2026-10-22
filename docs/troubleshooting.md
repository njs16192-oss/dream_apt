# Troubleshooting

## Docker daemon 연결 실패
- 증상: `failed to connect to the docker API at npipe:////./pipe/docker_engine`
- 원인: Docker Desktop 미실행
- 해결: Docker Desktop 실행 후 Engine running 확인

## npm run dev - package.json 없음
- 증상: `ENOENT: no such file or directory, open '...\package.json'`
- 원인: 프로젝트 루트에서 실행함
- 해결: `frontend` 폴더로 이동 후 실행