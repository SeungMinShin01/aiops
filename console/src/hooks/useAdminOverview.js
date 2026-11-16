import { useQuery } from "@tanstack/react-query";
import { getAdminOverview } from "../api/admin";
import { queryKeys } from "../api/queryKeys";

// 관리자 전체 현황. 켜 두고 보는 화면이라 30초마다 다시 불러온다
export function useAdminOverview() {
  return useQuery({
    queryKey: queryKeys.adminOverview,
    queryFn: () => getAdminOverview(),
    refetchInterval: 30_000,
  });
}