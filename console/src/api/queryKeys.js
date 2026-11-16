// TanStack Query 키는 여기 한 곳에만 둔다 (D-100). 화면에서 배열을 직접 쓰지 않는다.
// 팀 데이터는 ['teams', teamId, ...]로 시작 → 팀을 바꾸면 다른 팀 캐시와 섞이지 않는다
export const queryKeys = {
  me: ["me"],
  team: (teamId) => ["teams", teamId],
  connection: (teamId) => ["teams", teamId, "connection"],
  keys: (teamId) => ["teams", teamId, "keys"],
  invites: (teamId) => ["teams", teamId, "invites"],
  members: (teamId) => ["teams", teamId, "members"],
};
