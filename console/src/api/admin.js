import { request } from "./client";

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