import { useQuery } from "@tanstack/react-query";
import { listAdminKeys } from "../api/admin";
import { queryKeys } from "../api/queryKeys";

// 한 팀의 키 목록 (관리자용). 키 관리 창이 열려 있는 동안만 불러온다
export function useAdminKeys(teamId) {
  return useQuery({
    queryKey: queryKeys.adminKeys(teamId),
    queryFn: () => listAdminKeys(teamId),
  });
}