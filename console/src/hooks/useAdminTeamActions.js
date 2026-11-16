import { useMutation } from "@tanstack/react-query";
import { issueOwnerInvite, resumeTeam, retryDiscord, suspendTeam } from "../api/admin";
import { queryKeys } from "../api/queryKeys";
import { queryClient } from "../app/queryClient";

// 관리자 현황 표의 팀별 동작 (AI-101). 서버 응답이 기준이라 낙관적 갱신은 하지 않는다 —
// 성공하면 현황 표를 다시 불러온다
export function useAdminTeamActions() {
  const refresh = () => queryClient.invalidateQueries({ queryKey: queryKeys.adminOverview });

  const suspend = useMutation({
    mutationFn: (teamId) => suspendTeam(teamId),
    onSuccess: refresh,
  });

  const resume = useMutation({
    mutationFn: (teamId) => resumeTeam(teamId),
    onSuccess: refresh,
  });

  // 채널 만들기는 디스코드를 몇 번 부르는 일이라 수 초 걸린다 (끝날 때까지 기다리는 API)
  const retry = useMutation({
    mutationFn: (teamId) => retryDiscord(teamId),
    onSuccess: refresh,
  });

  // 조장 링크 원문은 쿼리 캐시에 남기지 않는다 (gcTime 0). 화면은 닫을 때 reset() 으로 결과를 지운다 (규칙 10)
  const ownerInvite = useMutation({
    mutationFn: (teamId) => issueOwnerInvite(teamId),
    gcTime: 0,
  });

  return { suspend, resume, retry, ownerInvite };
}