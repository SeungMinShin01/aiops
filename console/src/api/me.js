import { request } from "./client";

/**
 * GET /api/me — 내 정보와 소속 팀. 서버의 MeController.MeResponse와 같은 모양이다.
 * isAdmin·role은 메뉴를 숨기는 데만 쓴다 (권한 판단은 서버).
 *
 * @typedef {object} TeamItem
 * @property {number} teamId
 * @property {string} name
 * @property {'OWNER'|'MEMBER'} role
 *
 * @typedef {object} Me
 * @property {number} userId
 * @property {string} login
 * @property {string|null} name
 * @property {boolean} isAdmin
 * @property {TeamItem[]} teams
 */

/** @returns {Promise<Me>} */
export function getMe() {
  return request("GET", "/api/me");
}

/** POST /api/logout — 세션을 끝낸다 (204) */
export function logout() {
  return request("POST", "/api/logout");
}
