import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { issueKey, revokeKey } from "../api/team";
import { queryKeys } from "../api/queryKeys";
import { queryClient } from "../app/queryClient";
import { dateOnly, envLabel, timeAgo } from "../app/format";
import { useAdminKeys } from "../hooks/useAdminKeys";
import { ConfirmDialog } from "./ConfirmDialog";
import { KeyRevealOnce } from "./KeyRevealOnce";

// 한 팀의 API 키 관리 창 (관리자만, D-092): 목록·발급(local/prod)·폐기
// 원문은 발급 응답 → KeyRevealOnce → 닫으면 상태에서 지운다 (규칙 10). 막는 건 서버(AdminGuard)이고 화면은 보여 줄 뿐이다
// props: team({ teamId, name }), onClose
export function TeamKeysDialog({ team, onClose }) {
  const keys = useAdminKeys(team.teamId);
  const [issued, setIssued] = useState(null); // { env, key } — 창이 열려 있는 동안만
  const [revoking, setRevoking] = useState(null); // 폐기 확인 중인 키

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.adminKeys(team.teamId) });

  // gcTime 0: 발급 응답(원문)을 쿼리 캐시에 남기지 않는다
  const issue = useMutation({
    mutationFn: (env) => issueKey(team.teamId, env),
    gcTime: 0,
    onSuccess: (result) => {
      setIssued({ env: result.env, key: result.key });
      refresh();
    },
  });

  const revoke = useMutation({
    mutationFn: (keyId) => revokeKey(team.teamId, keyId),
    onSettled: () => {
      setRevoking(null);
      refresh();
    },
  });

  const closeReveal = () => {
    setIssued(null); // 원문을 상태에서 지운다
    issue.reset(); // 변경 결과(원문 포함)도 지운다
  };

  return (
    <>
      <div className="dialog-backdrop" role="presentation">
        <div className="dialog wide" role="dialog" aria-modal="true" aria-labelledby="team-keys-title">
          <h2 id="team-keys-title" className="dialog-title">{team.name} · API 키</h2>
          <p className="muted">
            원문은 발급한 직후 한 번만 보여요. 디스코드 DM으로 전해 주세요 (로컬 키는 조장, 운영 키는 배포 담당).
          </p>
          <div className="section-actions">
            <button type="button" className="button small" onClick={() => issue.mutate("local")} disabled={issue.isPending}>
              로컬 키 발급
            </button>
            <button type="button" className="button small" onClick={() => issue.mutate("prod")} disabled={issue.isPending}>
              운영 키 발급
            </button>
          </div>
          {issue.isError && <p className="error-text" role="alert">{issue.error.message}</p>}
          {revoke.isError && <p className="error-text" role="alert">{revoke.error.message}</p>}

          {keys.isPending && <p className="muted">불러오는 중…</p>}
          {keys.isError && <p className="error-text">키 목록을 불러오지 못했어요.</p>}
          {keys.data && keys.data.length === 0 && <p className="muted">아직 발급한 키가 없어요.</p>}
          {keys.data && keys.data.length > 0 && (
            <ul className="row-list">
              {keys.data.map((key) => (
                <li key={key.keyId} className={key.revokedAt ? "row revoked" : "row"}>
                  <div className="row-main">
                    <strong>{envLabel(key.env)}</strong>
                    <code>{key.prefix}…</code>
                    {key.revokedAt && <span className="tag">폐기됨</span>}
                  </div>
                  <div className="row-sub muted">
                    발급 {dateOnly(key.createdAt)} · 마지막 사용 {key.lastUsedAt ? timeAgo(key.lastUsedAt) : "없음"}
                  </div>
                  {!key.revokedAt && (
                    <button type="button" className="button small" onClick={() => setRevoking(key)}>
                      폐기
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          <div className="dialog-actions">
            <button type="button" className="button primary" onClick={onClose}>
              닫기
            </button>
          </div>
        </div>
      </div>

      {issued && <KeyRevealOnce issued={issued} onClose={closeReveal} />}
      {revoking && (
        <ConfirmDialog
          title={`${envLabel(revoking.env)} 키 ${revoking.prefix}… 를 폐기할까요?`}
          text="이 키로 오는 요청은 즉시 거부돼요. 되돌릴 수 없어요."
          confirmLabel="폐기"
          danger
          busy={revoke.isPending}
          onConfirm={() => revoke.mutate(revoking.keyId)}
          onCancel={() => setRevoking(null)}
        />
      )}
    </>
  );
}