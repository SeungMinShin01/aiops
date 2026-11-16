import { useQuery } from "@tanstack/react-query";
import { useParams, useSearchParams } from "react-router";
import { getConnection } from "../api/connection";
import { queryKeys } from "../api/queryKeys";
import { ConnectionBadge } from "../components/ConnectionBadge";
import { DiscordSection } from "../components/DiscordSection";
import { FirstSetupGuide } from "../components/FirstSetupGuide";
import { InviteSection } from "../components/InviteSection";
import { KeyTable } from "../components/KeyTable";
import { MemberTable } from "../components/MemberTable";
import { useMe } from "../hooks/useMe";
import "../styles/settings.css";

// /teams/:teamId/settings — 조장이 초대 링크로 들어와 가장 먼저 만나는 화면 (AI-106).
// 역할(role)·관리자 여부(isAdmin)는 메뉴를 숨기는 데만 쓴다. 권한 판단은 서버 (규칙 13)
const POLL_MS = 5000; // 연결 배지는 이 화면에서만 5초마다 새로 본다

export function TeamSettings() {
  const params = useParams();
  const [search] = useSearchParams();
  const me = useMe();
  const teamId = Number(params.teamId);

  const connection = useQuery({
    queryKey: queryKeys.connection(teamId),
    queryFn: () => getConnection(teamId),
    refetchInterval: POLL_MS,
  });

  if (!me.data) {
    return null; // RequireLogin·TeamLayout이 먼저 불러 둔다
  }
  const team = me.data.teams.find((item) => item.teamId === teamId);
  if (!team) {
    return null; // TeamLayout이 권한 없음 화면으로 보낸다
  }
  const isOwner = team.role === "OWNER";
  const isAdmin = me.data.isAdmin;

  return (
    <div className="settings">
      <h1 className="page-title">설정</h1>

      <section className="card" aria-labelledby="conn-title">
        <h2 id="conn-title" className="section-title">연결 상태</h2>
        <div className="conn-row">
          <ConnectionBadge env="local" status={connection.data ? connection.data.local : undefined} />
          <ConnectionBadge env="prod" status={connection.data ? connection.data.prod : undefined} />
        </div>
        {connection.isError && <p className="error-text">연결 상태를 불러오지 못했어요.</p>}
      </section>

      {search.get("first") === "1" && <FirstSetupGuide isAdmin={isAdmin} />}

      <KeyTable teamId={teamId} canManage={isAdmin} />

      {isOwner && <InviteSection teamId={teamId} />}

      <MemberTable teamId={teamId} myUserId={me.data.userId} canManage={isOwner} />

      <DiscordSection
        teamId={teamId}
        teamName={team.name}
        discordStatus={team.discordStatus}
        discordConnected={me.data.discordConnected}
        outcome={search.get("discord")}
      />
    </div>
  );
}
