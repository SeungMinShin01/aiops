# AIOps 작업 흐름 안내 (브랜치 · 커밋 · PR)

- 문서 버전: 1.0 (2026-10-29)
- 대상: 팀원 전원. 처음 작업 시작 전에 한 번 끝까지 읽는다.

---

## 0. 한 장 요약

```
Jira 하위 작업 하나 잡기
  → develop에서 브랜치 만들기      feat/starter-AI-36-event-buffer
  → 구현 + 테스트 작성
  → 커밋                          feat(starter): BoundedEventBuffer 구현 [AI-36]
  → 푸시 → GitHub에서 PR 열기 (대상: develop)
  → CI 초록 확인 → PR 템플릿 다 채웠는지 확인 → 본인이 Merge
  → Jira 하위 작업 "완료"로
```

- 작업 단위 1개 = 브랜치 1개 = PR 1개. 두 개 섞지 않는다.
- PR 대상은 항상 **develop**. main은 배포할 때만 설계자가 올린다.
- 승인은 필요 없다. **CI가 초록이면 본인이 머지**한다.
- 테스트 파일은 수정하지 않는다. 틀렸다고 생각되면 PR 본문에 적고 설계자에게 묻는다.

---

## 1. 처음 한 번만

```bash
git clone https://github.com/<깃허브아이디>/aiops.git
cd aiops
./gradlew build          # 초록이면 환경 OK
```

기본 브랜치는 `develop`이다. `git branch`로 `* develop`인지 확인한다.

로컬 DB가 필요한 작업(서버 쪽)은:

```bash
docker compose -f infra/local/docker-compose.yml up -d
```

---

## 2. 작업 시작

### 2-1. Jira에서 하위 작업 하나 잡기

- 자기 담당 스토리(예: `3 [Starter] 보내기`)를 열고 하위 작업 하나를 **진행 중**으로 바꾼다.
- 하위 작업 본문의 **목적 / 동작 / 지켜야 할 설계 규칙 / 하지 말 것 / 파일 / 확인**을 끝까지 읽는다. 여기 적힌 파일 경로와 클래스 이름을 그대로 쓴다.
- 이슈 키(예: `AI-36`)를 기억해 둔다. 브랜치·커밋·PR에 전부 들어간다.

### 2-2. 브랜치 만들기

항상 **최신 develop**에서 딴다.

```bash
git checkout develop
git pull origin develop
git checkout -b feat/starter-AI-36-event-buffer
```

브랜치 이름 규칙:

```
<타입>/<범위>-<이슈키>-<내용>

타입: feat | fix | refactor | test | docs | chore | build | ci
범위: protocol | starter | server | sample | console | infra | docs
내용: 영어 소문자, 하이픈
```

예:

| 작업 | 브랜치 |
|---|---|
| 버퍼 실구현 | `feat/starter-AI-36-event-buffer` |
| 키 인증 | `feat/server-AI-30-api-key-resolver` |
| 격리 테스트 통과시키기 | `feat/server-AI-70-tenant-context` |
| 문서 오타 | `docs/docs-AI-5-fix-typo` |

이슈 키가 들어가야 Jira 이슈 화면 "개발" 칸에 브랜치·PR이 자동으로 붙는다.

### 2-3. 선행 테스트(🔴)가 있는 작업이면

마스킹(AI-41), 지문(AI-51), 격리 8종(AI-69), 권한 E2E(AI-76)는 설계자가 **실패하는 테스트를 먼저** 써서 브랜치로 준다. 이 경우 develop이 아니라 **그 브랜치에서** 딴다.

```bash
git fetch origin
git checkout -b feat/starter-AI-41-masker origin/test/AI-41-masker
```

목표는 그 테스트를 **수정 없이** 초록으로 만드는 것이다.

---

## 3. 구현하면서

- 하위 작업의 "파일" 칸에 적힌 경로·클래스 이름을 쓴다. 다른 이름으로 만들면 다음 단계 사람이 못 찾는다.
- "하지 말 것" 칸은 그대로 금지 목록이다. 특히:
  - 새 라이브러리 의존성 추가 금지 (`build.gradle`에 줄 추가하지 않는다)
  - Starter 코드에서 예외를 밖으로 던지지 않는다
  - 키 원문·토큰·웹훅 주소를 코드·로그·테스트에 적지 않는다
  - 테스트 파일 수정, `@Disabled`, 테스트 입력값에 맞춘 하드코딩 금지
- 완료 기준에 적힌 테스트를 **같이 작성**한다. 테스트 없는 PR은 올리지 않는다.
- 설계와 다르게 해야 할 것 같으면 **코드를 짜기 전에 멈춘다.** Jira 하위 작업에 댓글로 "어느 규칙/결정(D-xxx)과 부딪히는지, 왜"를 적고 설계자에게 묻는다.

### AI(Claude 등)에게 시킬 때

```
✗ "이 테스트 통과시켜줘"
✓ "docs/aiops-v0.1-architecture.md 부품 ⑥ 명세와 Jira AI-36 본문대로 BoundedEventBuffer를 구현해줘.
   테스트 파일은 건드리지 마. 새 의존성 추가하지 마."
```

AI가 만든 코드가 테스트를 통과하면, **왜 통과하는지** 설계 문서와 대조해서 본인이 설명할 수 있어야 PR을 올린다. AI가 "설계를 바꾸자"고 하면 결정 번호(D-xxx)와 기각한 대안을 봤는지 확인한다. 없으면 설계를 안 읽은 것이다.

---

## 4. 커밋

```bash
git add -A
git commit -m "feat(starter): BoundedEventBuffer 구현 [AI-36]"
```

커밋 메시지 규칙:

```
<타입>(<범위>): <요약 한 줄> [<이슈키>]
```

- 요약은 한국어로, 무엇을 했는지 한 줄. 마침표 없음.
- 커밋은 여러 개여도 된다. 하지만 전부 같은 작업 단위여야 한다.
- 커밋 전에 로컬에서 한 번:

```bash
./gradlew build        # 컴파일 + 테스트 전부
```

---

## 5. 푸시하고 PR 열기

```bash
git push -u origin feat/starter-AI-36-event-buffer
```

푸시하면 터미널에 PR 만드는 링크가 뜬다. 또는 GitHub 레포 → **Pull requests → New pull request**.

- **base: `develop`** ← compare: 내 브랜치. base가 `main`이면 안 된다.
- 제목: `[AI-36] 버퍼 실구현` (이슈 키 + 하위 작업 제목)
- 본문: 자동으로 템플릿이 들어온다. **전부 채운다.**

PR 템플릿 항목:

```
## 작업 단위
AI-36 버퍼 실구현

## 한 것
- ArrayBlockingQueue 기반 BoundedEventBuffer 구현
- 1,001번째 offer → false, droppedCount 증가

## 테스트
- BoundedEventBufferTest: 용량 초과, drain 순서, takeDroppedCount 리셋

## 설계와 다른 점
없음 / 또는: 명세에는 X인데 Y로 했다. 이유: ... (설계자 확인 필요)

## 확인 (완료 기준)
- [x] 1,001번째 offer → false
- [x] 버린 개수 1
```

"설계와 다른 점"이 **없음**이 아니면 머지하지 말고 설계자 답을 기다린다.

---

## 6. CI 확인하고 머지

PR을 열면 몇 분 안에 `build` 체크가 돈다.

- **초록**: 본문 다 채웠으면 **Merge pull request → Confirm merge**. 승인 기다릴 필요 없다.
- **빨강**: 체크를 눌러 로그를 본다. 로컬에서 `./gradlew build`로 재현 → 고쳐서 같은 브랜치에 커밋·푸시하면 PR에 자동 반영되고 CI가 다시 돈다.
- 머지 방식은 기본값(Merge commit) 그대로. Squash·Rebase 쓰지 않는다.
- 머지 후 브랜치는 삭제해도 된다 (GitHub가 버튼을 보여준다).

머지되면 Jira 하위 작업을 **완료**로 바꾼다. 스토리의 하위 작업이 전부 완료되면 스토리도 완료.

---

## 7. 충돌이 났을 때

PR 화면에 "This branch has conflicts"가 뜨면:

```bash
git checkout develop
git pull origin develop
git checkout feat/starter-AI-36-event-buffer
git merge develop
# 충돌 파일 열어서 <<<<<<< ======= >>>>>>> 정리
git add -A
git commit -m "chore(starter): develop 병합 [AI-36]"
git push
```

같은 파일을 두 사람이 고쳤을 때 생긴다. 어느 쪽을 남길지 모르겠으면 그 파일 담당자(Jira에서 확인)에게 묻는다.

---

## 8. 하지 말 것 (요약)

| 하지 말 것 | 대신 |
|---|---|
| `develop`·`main`에 직접 푸시 | 브랜치 → PR |
| PR base를 `main`으로 | 항상 `develop` |
| 한 PR에 두 작업 단위 | 작업 단위마다 브랜치·PR |
| 테스트 파일 수정, `@Disabled` | PR 본문에 적고 설계자에게 |
| `build.gradle`에 의존성 추가 | 설계자에게 먼저 |
| 키·토큰·웹훅 주소 커밋 | 환경변수, `.env`(gitignore) |
| 설계와 다르게 하고 나중에 말하기 | 코드 짜기 전에 Jira 댓글 |
| 다른 사람 파일 수정 | 그 사람에게 말하고 그 사람 PR로 |

---

## 9. 자주 묻는 것

**Q. 하위 작업이 생각보다 커서 둘로 나누고 싶다.**
설계자에게 말하면 Jira에서 쪼개 준다. 혼자 쪼개서 브랜치 두 개 만들지 않는다.

**Q. 내 작업이 다른 사람 작업(아직 안 끝남)에 의존한다.**
Jira 스토리의 "선행" 칸 확인. 아직 없는 클래스는 인터페이스만 보고 가짜(Fake) 구현으로 테스트를 짠다. 그래도 막히면 설계자에게.

**Q. CI는 초록인데 로컬에선 빨강이다 (또는 반대).**
대부분 로컬 DB(docker compose) 안 켜짐, 또는 Java 버전 차이. `java -version`이 17인지, `docker ps`에 postgres가 있는지 확인.

**Q. PR 올렸는데 CI가 안 돈다.**
base가 `develop`인지 확인. 그래도 안 돌면 설계자에게.

**Q. 급한 수정인데 PR 거쳐야 하나.**
그렇다. 한 줄이어도 PR. 승인이 없으니 1분이면 된다.
