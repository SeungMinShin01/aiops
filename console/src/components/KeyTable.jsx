import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { issueKey, listKeys, revokeKey } from "../api/team";
import { queryKeys } from "../api/queryKeys";
import { queryClient } from "../app/queryClient";
import { dateOnly, envLabel, timeAgo } from "../app/format";
import { ConfirmDialog } from "./ConfirmDialog";
import { KeyRevealOnce } from "./KeyRevealOnce";

// 팀 API 키. 목록은 팀원 누구나, 발급·폐기는 관리자만 (D-092 — 버튼 숨김은 편의, 막는 건 서버)
// 원문은 발급 응답 → KeyRevealOnce → 닫으면 상태에서 지운다 (규칙 10)
// props: teamId, canManage(관리자면 true)
export function KeyTable({ teamId, canManage }) {
  const keys = useQuery({ queryKey: queryKeys.keys(teamId), queryFn: () => listKeys(teamId) });
  const [issued, setIssued] = useState(null); // { env, key } — 창이 열려 있는 동안만
  const [revoking, setRevoking] = useState(null); // 폐기 확인 중인 키

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.keys(teamId) });

  const issue = useMutation({
    mutationFn: (env) => issueKey(teamId, env),
    onSuccess: (result) => {
      setIssued({ env: result.env, key: result.key });
      refresh();
    },
  });

  const revoke = useMutation({
    mutationFn: (keyId) => revokeKey(teamId, keyId),
    onSuccess: () => {
      setRevoking(null);
      refresh();
    },
  });

  const closeReveal = () => setIssued(null); // 원문을 상태에서 지운다

  return (
    <section className="card" aria-labelledby="keys-title">
      <div className="section-head">
        <h2 id="keys-title" className="section-title">API 키</h2>
        {canManage && (
          <div className="section-actions">
            <button type="button" className="button small" onClick={() => issue.mutate("local")} disabled={issue.isPending}>
              로컬 키 발급
            </button>
            <button type="button" className="button small" onClick={() => issue.mutate("prod")} disabled={issue.isPending}>
              운영 키 발급
            </button>
          </div>
        )}
      </div>
      {issue.isError && <p className="error-text" role="alert">{issue.error.message}</p>}

      {keys.isPending && <p className="muted">불러오는 중…</p>}
      {keys.isError && <p className="error-text">키 목록을 불러오지 못했어요.</p>}
      {keys.data && keys.data.length === 0 && (
        <p className="muted">
          {canManage ? "아직 발급한 키가 없어요." : "아직 발급된 키가 없어요. 관리자에게 키를 요청하세요."}
        </p>
      )}
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
              {canManage && !key.revokedAt && (
                <button type="button" className="button small" onClick={() => setRevoking(key)}>
                  폐기
                </button>
              )}
            </li>
          ))}
        </ul>
      )}

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
    </section>
  );
}
