# 마일스톤 확인 기록

각 마일스톤을 끝에서 끝으로 직접 돌려 보고 날짜·캡처·확인 항목을 남긴다.
자동 확인은 통합 테스트로, 사람 눈으로 볼 것(디스코드 화면)은 여기 캡처로.

---

## 마일스톤 1: 샘플 앱 에러 → 서버 저장

(미확인, 4단계 후 승민 작성)
실행 절차는 README의 "로컬에서 끝까지 돌려보기 (마일스톤 1)"에 있다 (AI-44).

---

## 마일스톤 2: 에러 → 설명 → 디스코드 (AI-60)

연결 순서: 수집 → 그룹화(트랜잭션) → 설명(LLM) → 알림. 설명 실패·알림 실패는 서로를 막지 않는다.
설명을 못 만든 그룹은 `ai_explanation`이 NULL로 남고 `ExplanationRetryScheduler`(AI-57)가 5분마다 다시 시도한다 (알림은 다시 가지 않음).

### 자동 확인
- [x] `EndToEndFlowTest`: `/v1/events` 1건 → 처리 → 설명 저장 → 가짜 웹훅 POST 1번(설명 세 칸 포함), 같은 에러 1건 더 → POST 추가 없음, 횟수 2, 설명 그대로
- [x] `EndToEndFlowNoExplanationTest`: 설명을 못 만들면(일일 상한 0) 알림은 "설명 준비 중"으로 가고 `ai_explanation`은 NULL

### 수동 확인 (로컬: Postgres → 서버 → sample-app)
날짜: (미확인)

준비: `infra/local/.env`에 `AIOPS_LLM_API_KEY`(실제 설명) 또는 비워 둠(가짜 설명), 테스트 팀(1번)의 `discord_webhook_local`에 테스트 서버 웹훅 주소.

- [ ] `/boom/npe` 1회 → 30초 안에 디스코드 #local 에 embed, AI 설명 세 칸 포함. `error_group` 1행, `ai_explanation` 채워짐
- [ ] `/boom/npe` 9회 더 → 추가 알림 없음, `occurrence_count=10`, LLM 호출 1회 (`llm_usage.call_count` 1 증가)
- [ ] `/boom/nested`, `/boom/sql` → 각각 새 그룹·새 알림
- [ ] `/boom/sensitive` → 알림 도착, 메시지에 `***`만 있고 원문 없음
- [ ] LLM 키 없이(Fake) → 설명 칸에 "(가짜 응답)" 문장
- [ ] 캡처 1장

---

## 마일스톤 3

(예정)
