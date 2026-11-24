import { useState } from "react";
import { copyText, envLabel } from "../app/format";

// 발급한 키 원문을 딱 한 번 보여 주는 창 (규칙 10).
// 원문은 부모가 발급 응답에서 받아 넘기고, 닫으면 부모가 상태에서 지운다. 저장소·URL·로그에 남기지 않는다
// props: issued({ env, key }), onClose
export function KeyRevealOnce({ issued, onClose }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    setCopied(await copyText(issued.key));
  };

  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="key-title">
        <h2 id="key-title" className="dialog-title">{envLabel(issued.env)} 키가 발급됐어요</h2>
        <p className="warning-box" role="alert">
          이 창을 닫으면 다시 볼 수 없어요. 지금 복사해서 application.yml이나 .env에 넣어 두세요.
        </p>
        <code className="secret-box">{issued.key}</code>
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
