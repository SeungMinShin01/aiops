// 브라우저에 기억해 두는 "화면 편의" 값들. 로그인 정보·토큰은 절대 여기 두지 않는다 (D-020).
// 저장소를 못 쓰는 브라우저(사생활 보호 모드 등)에서도 화면은 그대로 동작해야 한다

const SIDEBAR_KEY = "aiops.sidebar.collapsed";
const LAST_TEAM_KEY = "aiops.lastTeamId";

function read(key) {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key, value) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // 기억 못 해도 동작에는 문제없다
  }
}

/** 왼쪽 팀 목록이 접혀 있었는지. 기억이 없으면 좁은 화면에서만 접힌 채로 시작 */
export function readSidebarCollapsed() {
  const saved = read(SIDEBAR_KEY);
  if (saved !== null) {
    return saved === "1";
  }
  return window.matchMedia("(max-width: 720px)").matches;
}

export function saveSidebarCollapsed(collapsed) {
  write(SIDEBAR_KEY, collapsed ? "1" : "0");
}

/** 마지막에 본 팀 번호. 기억이 없으면 null */
export function readLastTeamId() {
  const saved = read(LAST_TEAM_KEY);
  return saved === null ? null : Number(saved);
}

export function saveLastTeamId(teamId) {
  write(LAST_TEAM_KEY, String(teamId));
}
