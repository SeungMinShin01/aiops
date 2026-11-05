# 마일스톤 확인 기록

각 마일스톤을 끝에서 끝으로 직접 돌려 보고 날짜·캡처·확인 항목을 남긴다.
자동 확인은 통합 테스트로, 사람 눈으로 볼 것(디스코드 화면)은 여기 캡처로.

---

## 마일스톤 1

> 기존 기록 위치 확인 후 이곳으로 옮긴다 (AI-60 작업 항목).

---

## 마일스톤 2: 에러 → 설명 → 디스코드 (AI-60)

연결 순서: 수집 → 그룹화(트랜잭션) → 설명(LLM) → 알림. 설명 실패·알림 실패는 서로를 막지 않는다.

### 자동 확인
- [x] `EndToEndFlowTest`: `/v1/events` 1건 → 처리 → 가짜 웹훅 POST 1번, 같은 에러 1건 더 → POST 추가 없음, 횟수 2
- [ ] 위 테스트에 설명 포함 확인 추가 (7단계 `FakeLlmClient` 이후)

### 수동 확인 (로컬: Postgres → 서버 → sample-app)
날짜: (미확인)

- [ ] `/boom/npe` 1회 → 30초 안에 디스코드 #local 에 embed, AI 설명 세 칸 포함. `error_group` 1행, `ai_explanation` 채워짐
- [ ] `/boom/npe` 9회 더 → 추가 알림 없음, `occurrence_count=10`, LLM 호출 1회 (`llm_usage.count`)
- [ ] `/boom/nested`, `/boom/sql` → 각각 새 그룹·새 알림
- [ ] LLM 키 없이(Fake) `/boom/sensitive` → 알림 도착, "설명 준비 중", `ai_explanation` NULL, 메시지에 `***`·원문 없음
- [ ] 캡처 1장

### 막혀 있는 것
- 7단계 LLM 설명 (ExplanationService·LlmClient·FakeLlmClient, AI-12)
- Starter 실제 전송 (sample-app → `/v1/events`)

---

## 마일스톤 3

(예정)
