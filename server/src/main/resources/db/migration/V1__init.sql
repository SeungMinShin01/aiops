-- V1: MVP 1부(v0.1) 테이블 8개. 소유자는 aiops_owner (Flyway 접속 계정).
-- 팀 데이터 테이블은 전부 tenant_id를 갖는다 → 11단계 RLS를 컬럼 추가 없이 건다.
-- 한 번 머지되면 이 파일은 수정하지 않는다. 바꿀 게 있으면 V2, V3…를 새로 만든다.

-- 팀
CREATE TABLE tenant (
    id                     BIGSERIAL    PRIMARY KEY,
    name                   VARCHAR(100) NOT NULL,
    discord_webhook_local  TEXT,                       -- 비밀값. 응답·로그에 노출 금지 (규칙 10)
    discord_webhook_prod   TEXT,
    notify_local           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- API 키 (원문 저장 금지, SHA-256 hex만)
CREATE TABLE api_key (
    id          BIGSERIAL   PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenant(id),
    key_hash    CHAR(64)    NOT NULL UNIQUE,
    env         VARCHAR(10) NOT NULL CHECK (env IN ('local', 'prod')),
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 같은 원인끼리 묶은 그룹 (6단계가 채움)
-- status·resolved_at·feedback은 콘솔(16단계)용이라 V3에서 추가한다
CREATE TABLE error_group (
    id                BIGSERIAL    PRIMARY KEY,
    tenant_id         BIGINT       NOT NULL REFERENCES tenant(id),
    fingerprint       CHAR(64)     NOT NULL,
    exception_type    VARCHAR(300) NOT NULL,
    top_frame         VARCHAR(500),
    first_env         VARCHAR(10)  NOT NULL,
    first_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    occurrence_count  BIGINT       NOT NULL DEFAULT 1,
    ai_explanation    TEXT,
    ai_explained_at   TIMESTAMPTZ,
    UNIQUE (tenant_id, fingerprint)
);

-- Starter가 보낸 에러 원본 1건 = 1행 (2단계가 저장, 6단계가 그룹으로 묶음)
CREATE TABLE error_event (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant(id),
    env          VARCHAR(10)  NOT NULL,
    group_id     BIGINT       REFERENCES error_group(id),
    payload      JSONB        NOT NULL,                 -- protocol ErrorEvent를 받은 그대로
    app_name     VARCHAR(200),
    app_commit   VARCHAR(64),
    host_hash    VARCHAR(16),
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed    BOOLEAN      NOT NULL DEFAULT FALSE
);

-- hello 1건 = 1행 (앱이 켜질 때마다 INSERT. "마지막 hello" = max(received_at))
CREATE TABLE app_snapshot (
    id           BIGSERIAL    PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL REFERENCES tenant(id),
    env          VARCHAR(10)  NOT NULL,
    host_hash    VARCHAR(16),
    payload      JSONB        NOT NULL,                 -- protocol HelloRequest를 받은 그대로
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 팀·env·날짜별 받은 수·버린 수
CREATE TABLE ingest_stat (
    id              BIGSERIAL   PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES tenant(id),
    env             VARCHAR(10) NOT NULL,
    day             DATE        NOT NULL,
    received_count  BIGINT      NOT NULL DEFAULT 0,
    dropped_count   BIGINT      NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, env, day)
);

-- LLM 일일 호출 수 (서버 전체 상한. 팀 데이터가 아니므로 tenant_id·RLS 없음)
CREATE TABLE llm_usage (
    day         DATE    PRIMARY KEY,
    call_count  INT     NOT NULL DEFAULT 0
);

-- 앱 인스턴스(PC·서버 1대)별 마지막 신호. "연결됨" 판정의 근거 (D-086)
-- hello·하트비트·이벤트 묶음 중 무엇이든 받으면 UPSERT로 last_seen_at 갱신
CREATE TABLE heartbeat_last (
    id            BIGSERIAL    PRIMARY KEY,
    tenant_id     BIGINT       NOT NULL REFERENCES tenant(id),
    env           VARCHAR(10)  NOT NULL,
    host_hash     VARCHAR(16)  NOT NULL,
    app_name      VARCHAR(200),
    started_at    TIMESTAMPTZ,              -- 마지막 hello 시각 (이번 실행이 언제 시작됐나)
    last_seen_at  TIMESTAMPTZ  NOT NULL,
    UNIQUE (tenant_id, env, host_hash)
);

-- 인덱스
CREATE INDEX idx_error_event_tenant_received ON error_event (tenant_id, received_at);
CREATE INDEX idx_error_event_unprocessed     ON error_event (received_at) WHERE processed = FALSE;  -- 6단계 재처리용
CREATE INDEX idx_error_event_group           ON error_event (group_id);
CREATE INDEX idx_error_group_tenant_last     ON error_group (tenant_id, last_seen_at DESC);
CREATE INDEX idx_app_snapshot_tenant_env     ON app_snapshot (tenant_id, env, received_at DESC);