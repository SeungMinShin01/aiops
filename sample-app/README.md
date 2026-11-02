# sample-app

Starter를 달아 보는 예제 앱. 다른 조의 Spring Boot 앱 역할을 한다.
Starter는 의존성으로만 쓰고 내부 클래스를 직접 호출하지 않는다.

## 실행

로컬 Postgres(`infra/local`)와 서버(`:server:bootRun`, 8080)를 먼저 띄운다.

    $env:AIOPS_KEY="aiops_l_local-dev-key-0001"   # 로컬 시드 키
    ./gradlew :sample-app:bootRun                  # 8081

키 없이 실행하면 Starter가 경고 한 줄만 남기고 꺼진다.

시작 실패 재현:

    ./gradlew :sample-app:bootRun --args='--spring.profiles.active=fail'

## 엔드포인트 (전부 GET)

| 경로              | 재현하는 것                                                    | 서버에 수집 |
| ----------------- | -------------------------------------------------------------- | ----------- |
| `/ok`             | 정상 200. 응답 지연 비교용                                     | 아니오      |
| `/boom/npe`       | NullPointerException                                           | 예          |
| `/boom/sql`       | 없는 테이블 조회 → BadSqlGrammarException (원인 PSQLException) | 예          |
| `/boom/nested`    | 원인 3단 예외 (outer → middle → inner)                         | 예          |
| `/boom/caught`    | 응답은 200, 로그에만 ERROR                                     | 예          |
| `/boom/sensitive` | 메시지 속 password·이메일·토큰 → `***`로 도착해야 함           | 예 (마스킹) |
| `/boom/warn`      | WARN 로그만, 예외 없음                                         | 아니오      |

PowerShell에서는 `curl` 대신 `curl.exe`를 쓴다. (`curl`은 500 응답에서 예외를 던진다)
