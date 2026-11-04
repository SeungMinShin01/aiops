-- V3: 콘솔(v0.3) 테이블·컬럼 (AI-77). 소유자는 aiops_owner (Flyway 접속 계정).
-- 이미 적용된 V1·V2는 고치지 않는다. V3 이후 수정은 V4로 (적용된 파일을 고치면 체크섬 불일치).
--
-- 새 테이블 5개에는 RLS를 걸지 않는다 (D-023):
--   app_user·membership·invitation = 팀을 알기 전에 조회하는 테이블 (membership에 걸면 로그인 후 팀 목록이 항상 0건)
--   cohort·admin_access_log        = 관리자 전용
--   대신 리포지토리 메서드를 고정 조건으로만 노출한다.
-- aiops_app의 DML 권한은 01-roles.sh의 ALTER DEFAULT PRIVILEGES로 자동 부여 → 여기서 GRANT 하지 않는다.
-- 비밀값은 해시만 (token_hash, key_hash). 원문 컬럼 없음 (규칙 10). key_prefix는 복원 불가능한 앞 12~16자.
-- enum은 text + CHECK (Postgres enum 타입은 값 추가 시 마이그레이션이 번거롭다).

-- 로그인 사용자 (GitHub). 관리자 여부 컬럼은 두지 않는다 (관리자는 환경변수 GitHub id 목록)
CREATE TABLE app_user (
    id               BIGSERIAL    PRIMARY KEY,
    github_id        BIGINT       NOT NULL UNIQUE,
    login            TEXT         NOT NULL,
    name             TEXT,
    discord_user_id  TEXT         UNIQUE,                 -- 한 디스코드 계정은 한 사용자에만 연결 (AI-93, 충돌 시 409)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 사용자 ↔ 팀 (조장 OWNER / 팀원 MEMBER)
CREATE TABLE membership (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES app_user(id),
    tenant_id   BIGINT       NOT NULL REFERENCES tenant(id),
    role        TEXT         NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, tenant_id)
);

-- 초대 링크 (원문 토큰은 저장하지 않는다)
CREATE TABLE invitation (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant(id),
    token_hash  TEXT         NOT NULL UNIQUE,
    role        TEXT         NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
    max_uses    INT          NOT NULL,
    used_count  INT          NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_by  BIGINT       REFERENCES app_user(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 기수
CREATE TABLE cohort (
    id          BIGSERIAL  PRIMARY KEY,
    name        TEXT       NOT NULL,
    start_date  DATE,
    end_date    DATE
);

-- 관리자 접근 기록 (관리자 API 호출마다 1행)
CREATE TABLE admin_access_log (
    id             BIGSERIAL    PRIMARY KEY,
    admin_user_id  BIGINT       NOT NULL,
    tenant_id      BIGINT,
    action         TEXT         NOT NULL,
    at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 팀: 기수·종료일·상태·디스코드 자원 id (웹훅 주소는 V1 컬럼 그대로)
-- 기존 행 status는 ACTIVE (시드·도그푸딩 팀이 계속 동작)
ALTER TABLE tenant
    ADD COLUMN cohort_id                 BIGINT REFERENCES cohort(id),
    ADD COLUMN project_end_date          DATE,
    ADD COLUMN status                    TEXT NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('WAITING_OWNER', 'ACTIVE', 'SUSPENDED')),
    ADD COLUMN discord_role_id           TEXT,
    ADD COLUMN discord_category_id       TEXT,
    ADD COLUMN discord_channel_local_id  TEXT,
    ADD COLUMN discord_channel_prod_id   TEXT,
    ADD COLUMN discord_status            TEXT NOT NULL DEFAULT 'NONE'
        CHECK (discord_status IN ('NONE', 'READY', 'FAILED', 'SUSPENDED')),   -- SUSPENDED = 정지로 웹훅 삭제됨 (AI-94)
    ADD COLUMN discord_error             VARCHAR(100);   -- 채널 자동 생성 실패 단계 "<단계명>:<status>" (AI-92). 웹훅 url·토큰은 넣지 않는다

-- API 키: 기존 행의 새 컬럼은 NULL (시드 키는 created_by 없음)
ALTER TABLE api_key
    ADD COLUMN key_prefix    VARCHAR(16),                     -- 콘솔 키 목록 표시용 앞자리 (예: aiops_l_ab12). 원문 아님
    ADD COLUMN created_by    BIGINT REFERENCES app_user(id),
    ADD COLUMN last_used_at  TIMESTAMPTZ;                     -- "이 키가 쓰이고 있나" 표시용 (채우는 코드는 14단계)

-- 에러 그룹: 콘솔 상태·평가 (RLS는 V2 그대로)
ALTER TABLE error_group
    ADD COLUMN status    TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    ADD COLUMN feedback  TEXT CHECK (feedback IN ('UP', 'DOWN'));

CREATE INDEX idx_membership_user       ON membership (user_id);
CREATE INDEX idx_admin_access_log_at   ON admin_access_log (at);

-- ============================================================================
-- Grafana 구역 (AI-75 규칙, V2 참고): 컬럼 단위 권한은 새 컬럼에 자동으로 붙지 않는다
-- ============================================================================

-- invitation: 새 테이블이라 DEFAULT PRIVILEGES로 테이블 SELECT가 붙었다 → 회수 후 token_hash 빼고 다시
REVOKE SELECT ON invitation FROM grafana_ro;
GRANT SELECT (id, tenant_id, role, max_uses, used_count, expires_at, created_by, created_at) ON invitation TO grafana_ro;

-- tenant·api_key에 이번에 추가한 컬럼 (민감하지 않음)
GRANT SELECT (cohort_id, project_end_date, status, discord_role_id, discord_category_id,
              discord_channel_local_id, discord_channel_prod_id, discord_status, discord_error) ON tenant TO grafana_ro;
GRANT SELECT (key_prefix, created_by, last_used_at) ON api_key TO grafana_ro;
