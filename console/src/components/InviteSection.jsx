import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { createInvite, expireInvite, listInvites } from "../api/team";
import { queryKeys } from "../api/queryKeys";
import { queryClient } from "../app/queryClient";
import { copyText, dateOnly } from "../app/format";
import { ConfirmDialog } from "./ConfirmDialog";

// 팀원 초대 링크 (조장만 보인다. 막는 건 서버 @RequireOwner)
// 만든 링크 원문은 만든 직후 이 화면에서만 보인다 (목록 API는 원문을 주지 않는다)
// props: teamId
export function InviteSection({ teamId }) {
  const invites = useQuery({ queryKey: queryKeys.invites(teamId), queryFn: () => listInvites(teamId) });
  const [maxUses, setMaxUses] = useState(10);
  const [created, setCreated] = useState(null); // { inviteUrl, expiresAt, maxUses }
  const [copied, setCopied] = useState(false);
  const [expiring, setExpiring] = useState(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.invites(teamId) });

  const create = useMutation({
    mutationFn: () => createInvite(teamId, maxUses),
    onSuccess: (result) => {
      setCreated(result);
      setCopied(false);
      refresh();
    },
  });

  const expire = useMutation({
    mutationFn: (invitationId) => expireInvite(teamId, invitationId),
    onSuccess: () => {
      setExpiring(null);
      refresh();
    },
  });

  const submit = (event) => {
    event.preventDefault();
    create.mutate();
  };

  return (
    <section className="card" aria-labelledby="invite-title">
      <h2 id="invite-title" className="section-title">팀원 초대</h2>
      <form className="inline-form" onSubmit={submit}>
        <label>
          사용 인원
          <input
            type="number"
            min={1}
            max={50}
            value={maxUses}
            onChange={(event) => setMaxUses(Number(event.target.value))}
          />
        </label>
        <button type="submit" className="button primary small" disabled={create.isPending}>
          초대 링크 만들기
        </button>
      </form>
      {create.isError && <p className="error-text" role="alert">{create.error.message}</p>}

      {created && (
        <div className="created-link">
          <code>{created.inviteUrl}</code>
          <div className="copy-row-actions">
            <button
              type="button"
              className="button small"
              onClick={async () => setCopied(await copyText(created.inviteUrl))}
            >
              {copied ? "복사됨" : "복사"}
            </button>
            <span className="muted">
              {dateOnly(created.expiresAt)}까지 · 최대 {created.maxUses}명
            </span>
          </div>
        </div>
      )}

      <h3 className="subsection-title">사용 중인 링크</h3>
      {invites.isPending && <p className="muted">불러오는 중…</p>}
      {invites.isError && <p className="error-text">링크 목록을 불러오지 못했어요.</p>}
      {invites.data && invites.data.length === 0 && <p className="muted">사용 중인 링크가 없어요.</p>}
      {invites.data && invites.data.length > 0 && (
        <ul className="row-list">
          {invites.data.map((invite) => (
            <li key={invite.invitationId} className="row">
              <div className="row-main">
                남은 횟수 {invite.maxUses - invite.usedCount} / {invite.maxUses}
              </div>
              <div className="row-sub muted">{dateOnly(invite.expiresAt)}까지</div>
              <button type="button" className="button small" onClick={() => setExpiring(invite)}>
                만료시키기
              </button>
            </li>
          ))}
        </ul>
      )}

      {expiring && (
        <ConfirmDialog
          title="이 초대 링크를 만료시킬까요?"
          text="이 링크로는 더 이상 팀에 들어올 수 없어요."
          confirmLabel="만료시키기"
          danger
          busy={expire.isPending}
          onConfirm={() => expire.mutate(expiring.invitationId)}
          onCancel={() => setExpiring(null)}
        />
      )}
    </section>
  );
}
