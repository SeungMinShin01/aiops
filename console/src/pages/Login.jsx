import { useSearchParams } from "react-router";

// 로그인은 서버가 GitHub으로 보낸다. axios가 아니라 링크 이동이어야 한다 (redirect가 막히지 않게)
export function Login() {
  const [search] = useSearchParams();
  const failed = search.has("error");
  return (
    <div className="login">
      <div className="login-card">
        <h1 className="login-logo">AIOps</h1>
        <p className="muted">
          팀 앱의 에러를 모아 원인 후보와 함께 보여 줍니다.
        </p>
        {failed && (
          <p className="login-error" role="alert">
            로그인에 실패했어요. 다시 시도해 주세요.
          </p>
        )}
        <a className="button primary" href="/oauth2/authorization/github">
          GitHub으로 로그인
        </a>
      </div>
    </div>
  );
}
