import { useMutation } from "@tanstack/react-query";
import { changeErrorStatus, giveErrorFeedback } from "../api/errors";
import { queryKeys } from "../api/queryKeys";
import { queryClient } from "../app/queryClient";

// 에러 그룹의 상태 바꾸기·AI 설명 평가 (AI-100). 서버 응답이 기준이라 낙관적 갱신은 하지 않는다 —
// 성공하면 이 그룹의 상세와 그 팀의 목록을 다시 불러온다 (목록의 상태 표시·필터 결과가 어긋나지 않게)
export function useErrorActions(teamId, groupId) {
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: queryKeys.error(teamId, groupId) });
    queryClient.invalidateQueries({ queryKey: queryKeys.errorLists(teamId) });
  };

  const changeStatus = useMutation({
    mutationFn: (status) => changeErrorStatus(teamId, groupId, status),
    onSuccess: refresh,
  });

  const giveFeedback = useMutation({
    mutationFn: (feedback) => giveErrorFeedback(teamId, groupId, feedback),
    onSuccess: refresh,
  });

  return { changeStatus, giveFeedback };
}