import { useQuery } from "@tanstack/react-query";
import { getErrorGroup } from "../api/errors";
import { queryKeys } from "../api/queryKeys";

// 에러 그룹 하나의 상세
export function useErrorGroup(teamId, groupId) {
  return useQuery({
    queryKey: queryKeys.error(teamId, groupId),
    queryFn: () => getErrorGroup(teamId, groupId),
  });
}