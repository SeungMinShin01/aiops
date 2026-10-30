#!/bin/bash
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- 계정 3개. 역할 분리가 RLS의 전제:
    --   aiops_owner : Flyway 마이그레이션 전용. 테이블 소유자. (소유자는 RLS를 무시하므로 서버가 쓰면 안 됨)
    --   aiops_app   : 서버 런타임. 읽기·쓰기만, 소유자 아님 → RLS 적용 대상
    --   grafana_ro  : Grafana. 읽기 전용 (BYPASSRLS는 11단계에서 추가)
    CREATE ROLE aiops_owner LOGIN PASSWORD '${AIOPS_OWNER_PASSWORD}';
    CREATE ROLE aiops_app   LOGIN PASSWORD '${AIOPS_APP_PASSWORD}';
    CREATE ROLE grafana_ro  LOGIN PASSWORD '${GRAFANA_RO_PASSWORD}';

    GRANT CONNECT ON DATABASE aiops TO aiops_owner, aiops_app, grafana_ro;
    GRANT CREATE, USAGE ON SCHEMA public TO aiops_owner;
    GRANT USAGE ON SCHEMA public TO aiops_app, grafana_ro;

    -- owner가 앞으로 만드는 테이블·시퀀스에 자동으로 권한 부여
    ALTER DEFAULT PRIVILEGES FOR ROLE aiops_owner IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO aiops_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE aiops_owner IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO aiops_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE aiops_owner IN SCHEMA public
        GRANT SELECT ON TABLES TO grafana_ro;
EOSQL