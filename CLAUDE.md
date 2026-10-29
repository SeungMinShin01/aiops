# AIOps — 이 레포에서 AI와 작업할 때 (레포 루트 CLAUDE.md)

## 북극성

팀 프로젝트에 **의존성 한 줄과 키 하나**만 넣으면, 로컬 개발부터 배포·발표까지 생기는 에러와 숨은 문제를 자동으로 모아 한국어로 설명한다. **팀 앱을 절대 느리게 하거나 죽이지 않는다.**

## 작업 전에 반드시

1. Jira(Aiops_)에서 자기 하위 작업을 찾는다. 목적·동작·설계 규칙·하지 말 것·파일·확인 기준이 거기 있다. 전체 목록은 `docs/mvp-worklist.md`.
2. 해당 버전 설계 문서(`docs/aiops-v0.1-architecture.md`, `docs/v0.2-*.md`, `docs/v0.3-*.md`)의 **부품 명세와 결정 기록**을 읽는다.
3. 설계와 다르게 해야 할 것 같으면 **코드를 짜기 전에 멈춘다.** PR 설명이나 이슈에 "어느 결정(D-xxx)과 부딪히는지, 왜"를 적고 설계자에게 묻는다.
4. 작업 단위 1개 = 브랜치 1개 = **develop PR 1개**. CI 초록이면 본인이 머지한다. develop → main은 배포 시점에만, 설계자가 병합한다.
   브랜치 `<타입>/<범위>-<이슈키>-<내용>`, 커밋 `타입(범위): 요약 [이슈키]` (타입: feat/fix/refactor/test/docs/chore/build/ci, 범위: protocol/starter/server/sample/console/infra/docs). 자세한 순서는 `docs/team-guide-pr.md`.

## 고정 규칙 (어기면 머지 안 됨)

```
1. protocol 모듈의 클래스는 수정하지 않는다. 필드 추가만, 삭제·이름 변경 금지. 필요하면 설계자에게 먼저 요청한다.
2. 주어진 인터페이스를 구현만 한다. 메서드 이름·파라미터를 바꾸지 않는다.
3. 새 라이브러리 의존성을 추가하지 않는다. (Starter의 HTTP는 java.net.http.HttpClient만)
4. Starter 코드는 어떤 경우에도 예외를 밖으로 던지지 않는다.
5. 팀 앱 스레드(Appender, Mapper, Masker, Buffer.offer)에서는 대기·네트워크·파일 작업을 하지 않는다.
6. 설정 값, 요청 본문, 헤더를 읽거나 전송하지 않는다. (설정은 키 이름과 출처만)
7. 패키지 구조: dev.aiops.starter.{config, lifecycle, capture, mask, transport, env}
8. 완료 조건 테스트를 같이 작성하고, 통과해야 PR을 올린다.
9. 테스트 코드는 수정하지 않는다. 테스트가 틀렸다고 생각되면 PR 설명에 적고 설계자에게 묻는다.
   테스트 수정은 별도 PR, 설계자만 승인. @Disabled, 테스트 입력값 맞춤 하드코딩, 예외 삼켜서 통과시키기 금지.
10. 키 원문·봇 토큰·웹훅 주소·OAuth 비밀값·LLM API 키는 코드·로그·응답·프롬프트에 남기지 않는다. 환경변수로만.
11. 팀 데이터 테이블은 전부 tenant_id를 갖고 RLS 정책이 있어야 한다. 격리는 코드가 아니라 DB가 강제한다.
12. /api/teams/{teamId}/** 경로는 MembershipGuard를 거치지 않으면 만들 수 없다. 권한 판단은 백엔드에서만.
13. 탐지·계산·판정은 코드로, 설명·문장만 AI로. AI는 제안만 하고 실행은 사람이 한다.
```

## AI에게 시키는 방식

- "이 테스트 통과시켜줘" ✗ → "docs/의 부품 ⑤ 명세대로 Masker를 구현해줘. 테스트 파일은 건드리지 마" ✓
- 생성된 코드가 테스트를 통과하면, **왜 통과하는지**를 설계 문서와 대조해서 본인이 설명할 수 있어야 PR을 올린다.
- AI가 "설계를 바꾸자"고 하면 결정 번호(D-xxx)와 기각한 대안이 있는지 본다. 없으면 설계와 비교를 안 한 것이다.

## 문서 위치

| 파일 | 내용 |
|---|---|
| `docs/00-index.md` | 문서 목록, 공통 원칙, 담당표, 진행 순서 |
| `docs/mvp-requirements.md` | 기능 요구사항 69개, 비기능, 통합 시나리오 10단계 |
| `docs/mvp-worklist.md` | 작업 단위 83개, 담당, 만들 것, 확인 기준 |
| `docs/aiops-v0.1-architecture.md` | v0.1 부품 ⓟ·①~⑰, 결정 D-001~ |
| `docs/v0.2-multitenancy.md`, `docs/v0.3-web-console.md` | MVP 2부·3부 |
| `docs/v0.4 ~ v1.4` | 이후 버전 (지금은 참고만) |
| `docs/team-guide-flow.md` | 다른 조에게 설명할 때 쓰는 동작 흐름 |
| `docs/team-guide-pr.md` | 브랜치·커밋·PR 순서 (팀원용) |

## 현재 단계

MVP 1부 (v0.1). 0단계 완료, 1단계 진행 중. Spring Boot 4.1.1 / Gradle 9.7.1 / 컴파일 타깃 Java 17. (설계자가 갱신)
