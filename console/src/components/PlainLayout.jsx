import { Outlet } from "react-router";
import { useMe } from "../hooks/useMe";
import { TopBar } from "./TopBar";

export function PlainLayout() {
  const me = useMe();
  if (!me.data) {
    return null;
  }
  return (
    <div className="shell">
      <TopBar login={me.data.login} isAdmin={me.data.isAdmin} />
      <main className="content">
        <Outlet />
      </main>
    </div>
  );
}
