// TanStack Query 키는 여기 한 곳에만 둔다 (D-100). 화면에서 배열을 직접 쓰지 않는다.
// 팀 데이터는 ['teams', teamId, ...]로 시작 → 팀을 바꾸면 다른 팀 캐시와 섞이지 않는다
export const queryKeys = {
  me: ["me"],
  team: (teamId) => ["teams", teamId],
  connection: (teamId) => ["teams", teamId, "connection"],
  keys: (teamId) => ["teams", teamId, "keys"],
  invites: (teamId) => ["teams", teamId, "invites"],
  members: (teamId) => ["teams", teamId, "members"],
  // 조건(filter)이 키에 들어 있어서, 조건을 바꾸면 따로 불러오고 따로 기억한다
  errors: (teamId, filter) => ["teams", teamId, "errors", filter],
  error: (teamId, groupId) => ["teams", teamId, "error", groupId],
  // 관리자 데이터는 ['admin', ...]으로 시작
  adminOverview: ["admin", "overview"],
};