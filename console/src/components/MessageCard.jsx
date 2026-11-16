// 화면 가운데에 뜨는 안내 카드 (팀 선택, 팀 없음, 권한 없음 등)
export function MessageCard({ title, text, children }) {
  return (
    <div className="login">
      <div className="login-card">
        <h1 className="page-title">{title}</h1>
        {text && <p className="muted">{text}</p>}
        {children}
      </div>
    </div>
  );
}
