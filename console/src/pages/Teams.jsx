import { Navigate } from "react-router";
import { readLastTeamId } from "../app/storage";
import { MessageCard } from "../components/MessageCard";
import { useMe } from "../hooks/useMe";

// 로그인 직후 어느 팀으로 갈지 정한다: 마지막에 본 팀 → 없으면 첫 번째 팀.
// 팀을 바꾸는 것은 왼쪽 팀 목록에서 한다 (팀 선택 화면은 따로 두지 않는다)
export function Teams() {
  const me = useMe();
  if (!me.data) {
    return null;
  }
  const teams = me.data.teams;
  if (teams.length === 0) {
    return (
      <MessageCard
        title="소속 팀이 없어요"
        text="조장에게 초대 링크를 받아 주세요."
      />
    );
  }
  // 기억해 둔 번호가 지금 내 팀 목록에 없으면(팀에서 나감, 다른 계정) 첫 번째 팀으로
  const lastTeamId = readLastTeamId();
  const target = teams.find((team) => team.teamId === lastTeamId) ?? teams[0];
  return <Navigate to={`/teams/${target.teamId}/errors`} replace />;
}
