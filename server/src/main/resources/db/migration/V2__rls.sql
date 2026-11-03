-- V2: 팀 격리 (RLS, AI-72). 소유자는 aiops_owner (Flyway 접속 계정).
-- 격리는 코드가 아니라 DB가 강제한다 (규칙 11). 서버는 aiops_app(소유자 아님)으로 붙으므로 아래 정책을 따른다.
--
-- app.tenant_id는 트랜잭션이 열릴 때 TenantTransactionManager가 set_config(..., true)로 넣는다.
--   current_setting('app.tenant_id', true) : 변수가 없으면 에러 대신 NULL
--   NULLIF(..., '')                        : 한 번 설정됐던 연결은 트랜잭션 후 빈 문자열이 남을 수 있음 → NULL
--   NULL과 비교하면 항상 거짓 → 팀 미설정이면 0건 (D-017 fail closed)
-- USING      = SELECT/UPDATE/DELETE 대상 행 필터
-- WITH CHECK = INSERT/UPDATE 결과 행 검사 (다른 팀 번호로 저장 금지)
--
-- 걸지 않는 테이블: tenant, api_key(팀을 알아내기 전에 키로 조회), llm_usage(tenant_id 없는 전체 카운터)
-- FORCE ROW LEVEL SECURITY는 걸지 않는다 — owner(Flyway)가 마이그레이션·시드를 해야 한다.
-- 정책은 역할을 지정하지 않는다(PUBLIC) — 새 계정이 생겨도 격리된다.
-- 이후 팀 테이블을 추가하면 같은 마이그레이션에 같은 이름(tenant_isolation)으로 정책을 넣는다.

ALTER TABLE error_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON error_event
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE error_group ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON error_group
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE app_snapshot ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON app_snapshot
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

ALTER TABLE ingest_stat ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ingest_stat
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);

-- heartbeat_last는 ON CONFLICT ... DO UPDATE 업서트를 쓰므로 USING·WITH CHECK가 둘 다 평가된다
ALTER TABLE heartbeat_last ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON heartbeat_last
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::bigint);
