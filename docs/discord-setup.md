# 디스코드 준비 (서버 · 봇 · OAuth2 앱)

- 문서 버전: 1.0 (2026-11-03) · 관련: AI-90, 15단계 디스코드 자동화, D-069·D-070, 규칙 10·40
- 이 문서만 보고 다른 팀원이 **테스트 서버**를 똑같이 준비할 수 있어야 한다.
- **값(토큰·secret·id)은 이 문서·코드·yml·커밋·Jira·디스코드 채팅 어디에도 적지 않는다.** 환경변수 이름만 쓴다. 실수로 노출되면 즉시 재발급한다.

---

## 0. 무엇을 만드나

| 만들 것 | 개수 | 쓰는 곳 |
|---|---|---|
| 디스코드 서버(guild) | 운영 1 + 테스트 1 | 운영 = 팀들이 실제로 들어오는 서버, 테스트 = 개발 중 채널 생성·삭제를 마음껏 해보는 서버 (local 프로필은 테스트 서버 id) |
| Application (봇 + OAuth2 앱) | 1 | 봇 = 역할·채널·웹훅 생성 (REST만), OAuth2 = 팀원 [디스코드 연결] |

---

## 1. 서버(guild) 만들기

1. 디스코드 왼쪽 **+ (서버 추가) → 직접 만들기 → 나와 친구들을 위한 서버**. 이름 예: `AIOps`, 테스트용은 `AIOps-test`.
2. **사용자 설정(톱니) → 고급 → 개발자 모드** 켜기.
3. 왼쪽 서버 아이콘 **우클릭 → 서버 ID 복사** → `AIOPS_DISCORD_GUILD_ID`.

## 2. Application 만들기 (OAuth2 앱)

1. https://discord.com/developers/applications → **New Application** (이름 예: `aiops`).
2. 왼쪽 **OAuth2** 탭
   - **Client ID** → `AIOPS_DISCORD_CLIENT_ID`
   - **Client Secret → Reset Secret** → `AIOPS_DISCORD_CLIENT_SECRET` (한 번만 보인다)
   - **Redirects → Add Redirect**
     - 로컬: `http://localhost:8080/api/discord/callback`
     - 운영: `https://app.<도메인>/api/discord/callback`
     - → `AIOPS_DISCORD_REDIRECT_URI` (환경별로 위 둘 중 하나)
3. 팀원 연결에서 요청할 scope는 **`identify`, `guilds.join` 두 개만**. `email`·`guilds` 등은 추가하지 않는다.

## 3. 봇 토큰

1. 왼쪽 **Bot** 탭 → **Reset Token** → `AIOPS_DISCORD_BOT_TOKEN` (한 번만 보인다).
2. **Privileged Gateway Intents 3개(Presence, Server Members, Message Content)는 전부 끈다.** 게이트웨이에 연결하지 않고 REST만 쓴다.
3. **Public Bot**은 꺼 두는 것을 권장 (다른 사람이 우리 봇을 자기 서버에 초대하지 못하게).
4. 개인 디스코드 계정 토큰을 봇 토큰 대신 쓰지 않는다 (약관 위반).

## 4. 봇 초대 (권한 딱 3개)

1. 왼쪽 **OAuth2 → URL Generator**
   - Scopes: **`bot`** 만
   - Bot Permissions: 아래 3개만 체크

     | 권한 | 체크 |
     |---|---|
     | Manage Roles (역할 관리) | ✅ |
     | Manage Channels (채널 관리) | ✅ |
     | Manage Webhooks (웹후크 관리) | ✅ |
     | **Administrator (관리자)** | ❌ **절대 금지** (D-069) |
     | 그 외 전부 | ❌ |

   - 3개만 체크하면 링크 끝의 `permissions=` 값이 `805306384`가 된다. 다르면 체크를 다시 확인한다.
2. 생성된 URL을 브라우저에서 열어 **운영 서버·테스트 서버 둘 다** 초대한다.

## 5. 역할 순서 (안 하면 역할 부여가 403)

1. **서버 설정 → 역할**에서 봇 역할(`aiops`)을 **목록 맨 위 쪽으로 드래그**한다.
2. 디스코드는 자기보다 **아래** 역할만 부여·관리할 수 있다. 봇이 만드는 팀 역할(`team-<팀이름>`)은 맨 아래에 생기므로, 봇 역할이 위에 있으면 된다.

## 6. @everyone 기본 권한

- 팀 카테고리는 생성할 때 `permission_overwrites`로 @everyone의 보기를 막는다 (자동).
- 서버 전체의 @everyone "채널 보기"는 그대로 두고, **공지 채널 하나만 공개**로 두는 것이 안전하다 (팀원이 들어오자마자 볼 곳).

## 7. 환경변수 넣기

| 환경변수 | 내용 | 비밀값 |
|---|---|---|
| `AIOPS_DISCORD_BOT_TOKEN` | 봇 토큰 (3단계) | ✅ |
| `AIOPS_DISCORD_GUILD_ID` | 서버 id (1단계, local은 테스트 서버) | |
| `AIOPS_DISCORD_CLIENT_ID` | OAuth2 client id (2단계) | |
| `AIOPS_DISCORD_CLIENT_SECRET` | OAuth2 client secret (2단계) | ✅ |
| `AIOPS_DISCORD_REDIRECT_URI` | 콜백 주소 (2단계) | |

- 이름 목록은 `infra/local/.env.example`, `infra/prod/.env.example`에 있다. 복사해서 `.env`를 만들고 값을 넣는다. **`.env`는 `.gitignore`에 있어 커밋되지 않는다. 커밋하는 건 `.env.example`뿐.**
- 서버는 `application.yml`의 `aiops.discord.*`로 읽는다. 값이 비어 있으면 디스코드 연동만 건너뛰고 서버는 뜬다.
- 로컬에서 서버를 띄울 때는 IDE 실행 설정(Environment variables)이나 셸에 위 변수를 넣는다. Spring은 `.env` 파일을 자동으로 읽지 않는다.

## 8. 확인

봇이 서버에 들어왔고 토큰·서버 id가 맞으면 200과 서버 이름이 돌아온다.

Git Bash / macOS / Linux:

```bash
curl -s -H "Authorization: Bot $AIOPS_DISCORD_BOT_TOKEN" "https://discord.com/api/v10/guilds/$AIOPS_DISCORD_GUILD_ID" | head -c 200; echo
```

Windows PowerShell (`curl`이 아니라 `curl.exe`):

```powershell
curl.exe -s -H "Authorization: Bot $env:AIOPS_DISCORD_BOT_TOKEN" "https://discord.com/api/v10/guilds/$env:AIOPS_DISCORD_GUILD_ID"
```

| 결과 | 원인 |
|---|---|
| `{"id": "...", "name": "AIOps-test", ...}` | 정상 |
| `401 Unauthorized` | 토큰이 틀림 (`Bot ` 접두어 빠짐, 재발급 후 옛 토큰) |
| `403 Missing Access` / `10004 Unknown Guild` | 봇이 그 서버에 초대되지 않았거나 서버 id가 틀림 |

마지막으로 레포에 비밀값이 없는지 확인한다 (아무것도 안 나와야 한다):

```bash
git grep -n "<내 봇 토큰 앞 10글자>"
```

## 체크리스트

- [ ] 운영 서버·테스트 서버 생성, 서버 id 복사
- [ ] Application 생성, client id·secret, Redirect 2개 등록
- [ ] 봇 토큰 발급, Privileged Intents 전부 끔
- [ ] 봇 초대: scope `bot`, 권한 3개(`permissions=805306384`), Administrator 없음, 두 서버 모두
- [ ] 봇 역할을 역할 목록 위쪽으로
- [ ] `.env`에 값 넣음 (커밋 안 함)
- [ ] curl guild 조회 200
- [ ] `git grep`으로 토큰·secret 문자열 없음
