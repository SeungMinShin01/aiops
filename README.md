# AIOps

Spring Boot 앱에 **의존성 한 줄과 키 하나**만 넣으면, 로컬 개발부터 배포까지 생기는 에러를 자동으로 모아 한국어로 설명하고 디스코드로 알려주는 서비스.

> 지금은 MVP(v0.1 ~ v0.3) 개발 중이다. 다른 조가 쓸 수 있는 시작 가이드는 마일스톤 3(10단계) 이후 여기에 쓴다.

## 레포 구조

```
protocol/     Starter ↔ 서버가 주고받는 데이터 형식 (순수 Java record)
starter/      팀 앱에 붙는 라이브러리 (Logback 어펜더 → 버퍼 → 전송)
server/       수집·그룹화·LLM 설명·디스코드 알림·웹 콘솔 API (Spring Boot)
sample-app/   Starter를 붙여 보는 테스트용 앱
console/      웹 콘솔 (Vite + React, 17단계에서 추가)
infra/        local: 개발용 docker compose / prod: 운영 VM 설정
docs/         설계 문서, 요구사항, 작업 목록, 팀 가이드
CLAUDE.md     이 레포에서 AI와 작업할 때의 규칙
```

## 개발 환경

- JDK 17 이상 (컴파일 타깃 17), Gradle wrapper 9.7.1 포함
- Spring Boot 4.1.1
- Docker (로컬 Postgres)

```bash
./gradlew build                                   # 전체 빌드·테스트
docker compose -f infra/local/docker-compose.yml up -d   # 로컬 DB (1단계 이후)
```

### 로컬에서 끝까지 돌려보기 (마일스톤 1)

```powershell
cp infra/local/.env.example infra/local/.env                           # 0) 처음 한 번. 필요한 값만 채운다
docker compose -f infra/local/docker-compose.yml up -d                 # 1) DB
./gradlew :server:bootRun --args='--spring.profiles.active=local'      # 2) 서버 (새 터미널)
./gradlew :sample-app:bootRun                                          # 3) 샘플 앱 (새 터미널)
curl.exe -s http://localhost:8081/boom/npe                              # 4) 에러 내기
docker exec -it aiops-postgres psql -U postgres -d aiops -P pager=off -c "select id, payload->>'exceptionType', left(payload->>'message', 60) from error_event order by id desc limit 5;"   # 5) 확인
```

- 환경변수는 `infra/local/.env`에 넣는다. `./gradlew bootRun`이 자동으로 읽는다(`gradle/dotenv.gradle`). 어떤 값이 있는지는 `infra/local/.env.example`에 있다. `.env`는 커밋하지 않는다.
- `[aiops] aiops.api-key 없음, 비활성`이 뜨면 `.env`에 `AIOPS_KEY`가 있는지 확인한다. IDE 실행 버튼으로 띄우면 `.env`를 읽지 않으니 Gradle로 실행한다.
- 터미널에서 직접 넣은 환경변수가 있으면 그 값이 `.env`보다 우선한다. 테스트는 `.env`를 읽지 않는다.
- 시드 키는 로컬 전용이다. 서버는 `local` 프로필이어야 시드 키가 들어간다.
- PowerShell의 `curl`은 Invoke-WebRequest라서 `curl.exe`를 쓴다.

## 작업 흐름

- 작업은 Jira(Aiops\_) 하위 작업 단위로. 브랜치 → `develop` PR → CI 초록이면 본인이 머지.
- 순서와 규칙: [`docs/team-guide-pr.md`](docs/team-guide-pr.md)
- 설계·결정 기록: [`docs/00-index.md`](docs/00-index.md)부터
- AI에게 시킬 때: [`CLAUDE.md`](CLAUDE.md)
