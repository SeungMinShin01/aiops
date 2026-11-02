-- 로컬 개발용 시드. 운영에서는 절대 실행되지 않는다 (local 프로필에서만 locations에 포함).
-- 테스트 팀 1개 + local 키 1개.
-- 키 원문: aiops_l_local-dev-key-0001   ← Starter 설정 aiops.api-key 에 넣는 값
-- 아래는 그 원문의 SHA-256 hex. DB에는 해시만 둔다 (규칙 10).

INSERT INTO tenant (id, name, notify_local, created_at)
VALUES (1, '로컬 테스트 팀', true, now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO api_key (id, tenant_id, key_hash, env, created_at)
VALUES (1, 1, 'f0e8ca65e485ef8f10c8c35c7f0cb6417582105c7237e41f8cc692ac31f1e1f6', 'local', now())
ON CONFLICT (id) DO NOTHING;

-- 시퀀스를 시드 뒤로 맞춤 (안 하면 다음 INSERT가 id=1과 충돌)
SELECT setval('tenant_id_seq', (SELECT max(id) FROM tenant));
SELECT setval('api_key_id_seq', (SELECT max(id) FROM api_key));

-- 디스코드 웹훅은 8단계에서 psql로 직접 넣는다 (주소는 커밋 금지):
-- UPDATE tenant SET discord_webhook_local = '...' WHERE id = 1;