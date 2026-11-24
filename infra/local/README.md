# 로컬 개발 환경 (infra/local)

## DB 띄우기

```bash
docker compose -f infra/local/docker-compose.yml up -d
```

계정 3개(`aiops_owner`·`aiops_app`·`grafana_ro`)는 `init/01-roles.sh`가 처음 한 번 만든다.
테스트 팀(id=1)과 local 키는 서버가 local 프로필로 뜰 때 `server/src/main/resources/db/seed/R__seed_local.sql`이 넣는다.

## 디스코드 알림 받기 (테스트 채널 웹훅)

웹훅 주소는 비밀값이라 시드 파일·코드·README 어디에도 커밋하지 않는다 (규칙 10). 서버를 한 번 띄워 시드가 들어간 뒤, 내 테스트 채널 웹훅을 psql로 직접 넣는다:

```bash
docker exec -it aiops-postgres psql -U postgres -d aiops -c "UPDATE tenant SET discord_webhook_local = '<내 테스트 채널 웹훅 주소>' WHERE id = 1;"
```

- prod 채널도 시험하려면 `discord_webhook_prod`에 같은 방식으로 넣는다.
- local 알림을 끄려면 `UPDATE tenant SET notify_local = false WHERE id = 1;`
- 팀별 분당 알림 상한은 `server/src/main/resources/application.yml`의 `aiops.notify.per-minute` (기본 10).
