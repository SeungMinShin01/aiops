import { useQuery } from "@tanstack/react-query";
import { getMe } from "../api/me";
import { queryKeys } from "../api/queryKeys";

export function useMe() {
  return useQuery({ queryKey: queryKeys.me, queryFn: () => getMe() });
}
