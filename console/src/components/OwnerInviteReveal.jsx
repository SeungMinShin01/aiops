import { useState } from "react";
import { copyText, dateOnly } from "../app/format";

// 조장 초대 링크 원문을 딱 한 번 보여 주는 창 (규칙 10). 키 원문 창(KeyRevealOnce)과 같은 방식:
// 원문은 부모가 발급 응답에서 받아 넘기고, 닫으면 부모가 상태에서 지운다. 저장소·URL·로그에 남기지 않는다
// props: teamName, invite({ inviteUrl, expiresAt }), onClose
export function OwnerInviteReveal({ teamName, invite, onClose }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    setCopied(await copyText(invite.inviteUrl));
  };

  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="owner-invite-title">
        <h2 id="owner-invite-title" className="dialog-title">{teamName} 조장 초대 링크</h2>
        <p className="warning-box" role="alert">
          이 창을 닫으면 다시 볼 수 없어요. 지금 복사해서 조장에게 보내 주세요. 이전에 만든 링크는 이제 쓸 수 없어요.
        </p>
        <code className="secret-box">{invite.inviteUrl}</code>
        <p className="muted">{dateOnly(invite.expiresAt)}까지 · 한 번만 쓸 수 있어요</p>
        <div className="dialog-actions">
          <button type="button" className="button" onClick={copy}>
            {copied ? "복사됨" : "복사"}
          </button>
          <button type="button" className="button primary" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  );
}