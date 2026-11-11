-- V4: admin_access_log 는 추가만 한다 (AI-87, D-024).
-- aiops_app(서버 계정)은 읽기·INSERT 만 가능하고 UPDATE·DELETE 는 못 한다.
-- 보관 정리가 필요하면 owner 계정으로 수동 (MVP).
-- 01-roles.sh 는 DB 를 처음 만들 때 테이블이 생기기 전에 돌아서 REVOKE 를 거기 쓸 수 없다 → 마이그레이션에 둔다.
REVOKE UPDATE, DELETE ON admin_access_log FROM aiops_app;