// "정말 할까요?" 확인 창. 되돌릴 수 없는 동작(키 폐기, 팀원 내보내기, 링크 만료) 앞에 띄운다
// props: title, text, confirmLabel, danger(빨간 버튼), busy(처리 중), onConfirm, onCancel
export function ConfirmDialog({ title, text, confirmLabel, danger, busy, onConfirm, onCancel }) {
  return (
    <div className="dialog-backdrop" role="presentation">
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="confirm-title">
        <h2 id="confirm-title" className="dialog-title">{title}</h2>
        {text && <p className="muted">{text}</p>}
        <div className="dialog-actions">
          <button type="button" className="button" onClick={onCancel} disabled={busy}>
            취소
          </button>
          <button
            type="button"
            className={danger ? "button danger" : "button primary"}
            onClick={onConfirm}
            disabled={busy}
          >
            {busy ? "처리 중…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
