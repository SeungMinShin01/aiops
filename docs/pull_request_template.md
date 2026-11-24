## 작업 단위

<!-- Jira 하위 작업 키와 제목. 예: AI-36 버퍼 실구현 -->

## 한 것

## <!-- 무엇을 만들었는지 2~5줄 -->

## 테스트

## <!-- 추가·변경한 테스트 클래스와 확인한 경우 -->

## 설계와 다른 점

<!-- 없으면 "없음". 있으면: 명세에는 X인데 Y로 했다. 이유. (설계자 확인 전 머지 금지) -->

없음

## 확인 (완료 기준)

<!-- Jira 하위 작업의 "확인" 칸을 그대로 옮겨서 체크 -->

- [ ]

## 격리 점검표 (DB·서버 데이터 접근을 바꿨을 때만)

- [ ] 서버 접속 계정(aiops_app)이 테이블 소유자가 아님
- [ ] tenant_id가 있는 팀 테이블 전부에 정책 `tenant_isolation`
- [ ] 정책에 USING과 WITH CHECK 둘 다
- [ ] `set_config('app.tenant_id', ?, true)` — 세 번째 인자 true
- [ ] `NULLIF(current_setting('app.tenant_id', true), '')`
- [ ] TenantContext를 set한 곳이 finally에서 clear
- [ ] 다른 스레드로 넘길 때 tenantId는 파라미터로 (ThreadLocal 복사 금지)
