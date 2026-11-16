import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getErrorGroups } from "../api/errors";
import { queryKeys } from "../api/queryKeys";

// 에러 목록 한 쪽. 조건을 바꿔 새로 불러오는 동안에는 이전 목록을 그대로 보여 준다 (화면이 깜빡이지 않게)
export function useErrorGroups(teamId, filter) {
  return useQuery({
    queryKey: queryKeys.errors(teamId, filter),
    queryFn: () => getErrorGroups(teamId, filter),
    placeholderData: keepPreviousData,
  });
}
