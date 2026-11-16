# AIOps 콘솔

팀원이 로그인해서 자기 팀의 에러를 보는 웹 화면이다. React + Vite, JavaScript(JSX).

## 실행

`console` 폴더에서 실행한다 (레포 루트가 아니다).

    npm install          # 처음 한 번
    npm run dev:mock     # 가짜 응답으로 띄우기 (서버 없이 화면만). http://localhost:5173
    npm run dev          # 진짜 서버(localhost:8080)에 연결
    npm run build        # 배포용 빌드 (dist/)
    npm run lint         # 코드 검사

- `dev:mock`은 항상 로그인된 상태다. 로그인 화면은 주소창에 `/login`을 직접 입력해서 본다.
- `dev`는 서버가 떠 있어야 하고, GitHub 로그인을 쓰려면 서버 `.env`에 GitHub OAuth 값이 있어야 한다.
  콘솔을 거쳐 로그인하므로 OAuth 앱의 콜백 주소는 `http://localhost:5173/login/oauth2/code/github` 이다.

## 폴더

| 폴더 | 내용 |
| --- | --- |
| `src/api` | 서버 호출. `client.js` 한 곳을 거친다. API마다 파일 하나, 응답 모양은 주석(JSDoc)에 적는다 |
| `src/app` | 앱 전체 설정 (`queryClient.js`, 브라우저 저장소 `storage.js`) |
| `src/hooks` | 데이터를 가져오는 훅 (`useMe` 등) |
| `src/components` | 여러 화면이 같이 쓰는 부품 (위쪽 메뉴, 왼쪽 팀 목록, 레이아웃) |
| `src/pages` | 주소 하나에 대응하는 화면 |
| `src/mocks` | 가짜 응답 (msw). `dev:mock`에서만 쓰이고 빌드 결과에는 들어가지 않는다 |
| `src/styles` | `base.css` 하나. 색·간격은 맨 위 변수로 |

## 규칙

- 화면에서 `axios`·`fetch`를 직접 부르지 않는다. `src/api/*`의 함수만 쓴다.
- 로그인 상태는 세션 쿠키뿐이다. 토큰을 `localStorage`·`sessionStorage`에 두지 않는다.
  브라우저에 저장하는 것은 화면 편의 값(목록 접힘, 마지막에 본 팀)뿐이고 `src/app/storage.js`에만 둔다.
- 권한 판단은 서버가 한다. `isAdmin`·`role`은 메뉴를 숨기는 데만 쓴다. 401이면 로그인 화면, 403이면 "권한 없음".
- 콘솔과 API는 같은 도메인이다. 서버에 CORS 설정을 추가하지 않는다 (개발 중에는 `vite.config.js`의 프록시가 맞춰 준다).
- TanStack Query의 키는 `src/api/queryKeys.js`에만 둔다. 팀 데이터의 키는 `['teams', teamId, ...]`로 시작한다.
- 새 API를 붙일 때: ① `src/api`에 함수와 응답 모양 주석 ② `queryKeys.js`에 키 ③ `src/mocks/handlers.js`에 가짜 응답 ④ 화면.

## 화면 시안

색·배치는 「AIOps 콘솔 화면 시안」 캔버스를 따른다 (웹 3장, 앱 2장).
환경은 화면에서 "로컬 / 운영"으로 적고, 에러 위치는 "○○ 클래스의 ○○ 메서드"로 풀어 쓴다.
