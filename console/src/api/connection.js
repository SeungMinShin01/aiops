import { request } from "./client";

// 연결 확인 (AI-98). 팀 설정 화면(AI-106)과 에러 목록 위쪽 배지(AI-100)가 같이 쓴다.
// 서버 값 local·prod는 화면에서 "로컬·운영"으로 적는다

/**
 * GET /api/teams/{teamId}/connection — env별 마지막 신호 (Cache-Control: no-store)
 * @typedef {object} ConnectionStatus
 * @property {'CONNECTED'|'NOT_CONNECTED'|'NEVER'} state  마지막 신호가 3분 안이면 CONNECTED, 신호가 없으면 NEVER
 * @property {string|null} lastSeenAt
 * @property {number} hostCount  최근 3분 안에 신호를 보낸 호스트 수
 *
 * @typedef {object} Connection
 * @property {ConnectionStatus} local
 * @property {ConnectionStatus} prod
 * @returns {Promise<Connection>}
 */
export function getConnection(teamId) {
  return request("GET", `/api/teams/${teamId}/connection`);
}
