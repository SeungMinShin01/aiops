-- 로컬 개발용 시드. 운영에서는 절대 실행되지 않는다 (local 프로필에서만 locations에 포함).
-- 테스트 팀 1개 + local 키 1개 + 로컬 로그인 사용자 1명(조장).
-- 키 원문: aiops_l_local-dev-key-0001   ← Starter 설정 aiops.api-key 에 넣는 값
-- 아래는 그 원문의 SHA-256 hex. DB에는 해시만 둔다 (규칙 10).

INSERT INTO tenant (id, name, notify_local, status, created_at)
VALUES (1, '로컬 테스트 팀', true, 'ACTIVE', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO api_key (id, tenant_id, key_hash, env, created_at)
VALUES (1, 1, 'f0e8ca65e485ef8f10c8c35c7f0cb6417582105c7237e41f8cc692ac31f1e1f6', 'local', now())
ON CONFLICT (id) DO NOTHING;

-- 로컬 로그인 확인용 사용자 1명 + 테스트 팀 조장 (V3). github_id는 GitHub 공개 사용자 번호 (비밀값 아님)
-- 다른 팀원은 자기 github_id로 줄을 추가해서 쓰면 된다 (https://api.github.com/users/<아이디> 의 "id")
INSERT INTO app_user (id, github_id, login, created_at)
VALUES (1, 256240676, 'hanwooya361', now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO membership (id, user_id, tenant_id, role, created_at)
VALUES (1, 1, 1, 'OWNER', now())
ON CONFLICT (id) DO NOTHING;

-- 시퀀스를 시드 뒤로 맞춤 (안 하면 다음 INSERT가 id=1과 충돌)
SELECT setval('tenant_id_seq', (SELECT max(id) FROM tenant));
SELECT setval('api_key_id_seq', (SELECT max(id) FROM api_key));
SELECT setval('app_user_id_seq', (SELECT max(id) FROM app_user));
SELECT setval('membership_id_seq', (SELECT max(id) FROM membership));

-- 디스코드 웹훅은 8단계에서 psql로 직접 넣는다 (주소는 커밋 금지):
-- UPDATE tenant SET discord_webhook_local = '...' WHERE id = 1;