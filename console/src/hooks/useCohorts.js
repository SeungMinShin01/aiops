import { useQuery } from "@tanstack/react-query";
import { listCohorts } from "../api/admin";
import { queryKeys } from "../api/queryKeys";

// 기수 목록 (팀 일괄 생성 화면에서 기수를 고를 때)
export function useCohorts() {
  return useQuery({
    queryKey: queryKeys.adminCohorts,
    queryFn: () => listCohorts(),
  });
}