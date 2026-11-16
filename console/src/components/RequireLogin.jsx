import { Outlet } from "react-router";
import { useMe } from "../hooks/useMe";

// 로그인해야 보이는 화면의 문지기. 401이면 client.js가 /login으로 보낸다
export function RequireLogin() {
  const me = useMe();
  if (me.isPending) {
    return <p className="page-message">불러오는 중…</p>;
  }
  if (me.isError) {
    return (
      <p className="page-message">
        정보를 불러오지 못했습니다. 새로고침해 주세요.
      </p>
    );
  }
  return <Outlet />;
}
