import { request } from "./client";

// 관리자 화면이 쓰는 서버 API (13단계, AI-82~92·AI-88). 응답 모양은 서버 dto와 같다.
// 모든 주소가 /api/admin/** 라서 관리자가 아니면 403 → client.js가 ForbiddenError로 던진다.
// 에러 응답은 { error, message } 이고 message를 화면에 그대로 보여 준다

/**
 * 관리자 전체 현황 한 줄 (팀 하나). 서버 dto/OverviewRow와 같은 모양이다.
 *
 * @typedef {object} OverviewRow
 * @property {number} teamId
 * @property {string} name
 * @property {string|null} cohort  기수 이름
 * @property {'WAITING_OWNER'|'ACTIVE'|'SUSPENDED'} status
 * @property {string|null} ownerLogin  조장이 아직 없으면 null
 * @property {string|null} lastSeenLocal  로컬의 마지막 신호 시각. 한 번도 없으면 null
 * @property {string|null} lastSeenProd   운영의 마지막 신호 시각. 한 번도 없으면 null
 * @property {number|null} errors24h  최근 24시간 에러 수. 집계에 실패한 팀은 null
 * @property {'NONE'|'READY'|'FAILED'|'SUSPENDED'} discordStatus
 */

/**
 * GET /api/admin/overview — 관리자만. 아니면 403
 * @returns {Promise<OverviewRow[]>}
 */
export function getAdminOverview() {
  return request("GET", "/api/admin/overview");
}

/**
 * 기수. 서버 dto/CohortResponse
 * @typedef {object} Cohort
 * @property {number} cohortId
 * @property {string} name
 * @property {string|null} startDate  '2026-09-01'
 * @property {string|null} endDate
 * @property {number} teamCount  그 기수에 속한 팀 수
 */

/**
 * GET /api/admin/cohorts — 기수 목록 (최신 생성 순)
 * @returns {Promise<Cohort[]>}
 */
export function listCohorts() {
  return request("GET", "/api/admin/cohorts");
}

/**
 * POST /api/admin/cohorts — 기수 만들기. 날짜는 비워도 되고(null), 둘 다 있으면 끝 ≥ 시작
 * @param {{name: string, startDate: string|null, endDate: string|null}} body
 * @returns {Promise<Cohort>}  201
 */
export function createCohort(body) {
  return request("POST", "/api/admin/cohorts", body);
}

/**
 * 만들어진 팀 한 줄. 서버 dto/TeamRow
 * @typedef {object} TeamRow
 * @property {number} teamId
 * @property {string} name
 * @property {'WAITING_OWNER'|'ACTIVE'|'SUSPENDED'} status
 * @property {'NONE'|'READY'|'FAILED'|'SUSPENDED'} discordStatus
 * @property {string|null} projectEndDate
 * @property {string|null} ownerLogin
 */

/**
 * POST /api/admin/teams/bulk — 기수 안에 팀 한꺼번에 만들기 (1~50개)
 * 400 이름 없음·개수 초과, 404 없는 기수, 409 이름 겹침 (하나라도 걸리면 아무것도 안 만든다)
 * 검사를 통과한 뒤 일부 팀만 저장에 실패할 수도 있어서, 돌려받은 개수가 보낸 개수보다 적을 수 있다
 * @param {number} cohortId
 * @param {{name: string, projectEndDate: string|null}[]} teams
 * @returns {Promise<TeamRow[]>}  201
 */
export function bulkCreateTeams(cohortId, teams) {
  return request("POST", "/api/admin/teams/bulk", { cohortId, teams });
}

/**
 * POST /api/admin/teams/{teamId}/owner-invite — 조장 초대 링크 발급. 이전 링크는 자동으로 무효
 * 원문(inviteUrl)은 이 응답에만 나온다. 409 이미 조장이 있거나 정지된 팀
 * @param {number} teamId
 * @returns {Promise<{inviteUrl: string, expiresAt: string}>}  201
 */
export function issueOwnerInvite(teamId) {
  return request("POST", `/api/admin/teams/${teamId}/owner-invite`);
}

/**
 * POST /api/admin/teams/{teamId}/suspend — 팀 정지 (이미 정지여도 200)
 * @param {number} teamId
 * @returns {Promise<{teamId: number, status: 'SUSPENDED'}>}
 */
export function suspendTeam(teamId) {
  return request("POST", `/api/admin/teams/${teamId}/suspend`);
}

/**
 * POST /api/admin/teams/{teamId}/resume — 팀 재개. 조장이 없던 팀은 WAITING_OWNER 로 돌아간다
 * @param {number} teamId
 * @returns {Promise<{teamId: number, status: 'ACTIVE'|'WAITING_OWNER'}>}
 */
export function resumeTeam(teamId) {
  return request("POST", `/api/admin/teams/${teamId}/resume`);
}

/**
 * POST /api/admin/teams/{teamId}/discord/retry — 디스코드 채널 다시 만들기. 끝날 때까지 기다린다 (수 초)
 * 409 정지된 팀·만드는 중, 503 서버에 봇 설정 없음. 웹훅 주소·채널 id는 응답에 없다
 * @param {number} teamId
 * @returns {Promise<{teamId: number, result: 'READY'|'ALREADY_READY'|'FAILED', discordStatus: string, discordError: string|null}>}
 */
export function retryDiscord(teamId) {
  return request("POST", `/api/admin/teams/${teamId}/discord/retry`);
}

/**
 * GET /api/admin/teams/{teamId}/keys — 그 팀의 키 목록 (원문·해시 없음). 팀용 목록(team.js listKeys)은 소속이어야 열려서
 * 관리자는 이 주소로 본다. 응답 모양은 team.js 의 ApiKeySummary 와 같다
 * @param {number} teamId
 * @returns {Promise<import("./team").ApiKeySummary[]>}
 */
export function listAdminKeys(teamId) {
  return request("GET", `/api/admin/teams/${teamId}/keys`);
}