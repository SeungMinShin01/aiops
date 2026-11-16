import { request } from "./client";

/**
 * GET /api/me — 내 정보와 소속 팀. 서버의 MeController.MeResponse와 같은 모양이다.
 * isAdmin·role은 메뉴를 숨기는 데만 쓴다 (권한 판단은 서버).
 *
 * @typedef {object} TeamItem
 * @property {number} teamId
 * @property {string} name
 * @property {'OWNER'|'MEMBER'} role
 * @property {'NONE'|'READY'|'FAILED'|'SUSPENDED'} discordStatus  팀 디스코드 채널 준비 상태 (D-110)
 *
 * @typedef {object} Me
 * @property {number} userId
 * @property {string} login
 * @property {string|null} name
 * @property {boolean} isAdmin
 * @property {TeamItem[]} teams
 * @property {boolean} discordConnected  내가 디스코드를 연결했는지. [디스코드 연결] 버튼을 이 값으로 정한다 (D-110)
 */

/** @returns {Promise<Me>} */
export function getMe() {
  return request("GET", "/api/me");
}

/** POST /api/logout — 세션을 끝낸다 (204) */
export function logout() {
  return request("POST", "/api/logout");
}
