import { useEffect, useState } from "react";
import { Navigate, Outlet, useParams } from "react-router";
import {
  readSidebarCollapsed,
  saveLastTeamId,
  saveSidebarCollapsed,
} from "../app/storage";
import { useMe } from "../hooks/useMe";
import { TeamSidebar } from "./TeamSidebar";
import { TopBar } from "./TopBar";

export function TeamLayout() {
  const params = useParams();
  const me = useMe();
  const [collapsed, setCollapsed] = useState(() => readSidebarCollapsed());

  const teamId = Number(params.teamId);
  const team = me.data
    ? me.data.teams.find((item) => item.teamId === teamId)
    : undefined;
  const currentTeamId = team ? team.teamId : null;

  // 지금 보는 팀을 기억해 둔다 → 다음 로그인 때 이 팀으로 바로 들어온다
  useEffect(() => {
    if (currentTeamId !== null) {
      saveLastTeamId(currentTeamId);
    }
  }, [currentTeamId]);

  if (!me.data) {
    return null; // RequireLogin이 먼저 불러 둔다
  }
  if (!team) {
    return <Navigate to="/forbidden" replace />; // 목록에 없는 팀. 실제 차단은 서버가 한다
  }

  const toggle = () => {
    const next = !collapsed;
    setCollapsed(next);
    saveSidebarCollapsed(next);
  };

  return (
    <div className="shell">
      <TopBar
        login={me.data.login}
        isAdmin={me.data.isAdmin}
        teamId={team.teamId}
        onMenu={toggle}
      />
      <div className="shell-body">
        <TeamSidebar
          teams={me.data.teams}
          collapsed={collapsed}
          onToggle={toggle}
        />
        <div className="shell-main">
          <div className="team-heading">
            <strong>{team.name}</strong>
            <span>· {team.role === "OWNER" ? "조장" : "팀원"}</span>
          </div>
          <main className="content">
            <Outlet />
          </main>
        </div>
      </div>
    </div>
  );
}
