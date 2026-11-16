import { request } from "./client";

/**
 * 에러 그룹 목록 한 줄. 서버 dto/ErrorGroupSummary와 같은 모양이다.
 *
 * @typedef {object} ErrorGroupSummary
 * @property {number} id
 * @property {string} exceptionType  예: 'java.lang.NullPointerException'
 * @property {string|null} location  예: 'dev.shop.order.OrderService#place' (모르면 null)
 * @property {'local'|'prod'} env
 * @property {'OPEN'|'RESOLVED'|'IGNORED'} status
 * @property {number} count
 * @property {string} firstSeen  시각 (ISO 문자열)
 * @property {string} lastSeen   시각 (ISO 문자열)
 * @property {boolean} hasExplanation
 *
 * 서버가 주는 한 쪽 (Spring Page)
 * @typedef {object} ErrorGroupPage
 * @property {ErrorGroupSummary[]} content
 * @property {number} totalElements  조건에 맞는 전체 개수
 * @property {number} totalPages
 * @property {number} number  지금 쪽 번호 (0부터)
 * @property {number} size
 *
 * 화면이 고르는 조건
 * @typedef {object} ErrorGroupFilter
 * @property {''|'local'|'prod'} env  빈 문자열이면 전체
 * @property {''|'OPEN'|'RESOLVED'|'IGNORED'} status  빈 문자열이면 전체
 * @property {number} days  최근 며칠
 * @property {'lastSeen,desc'|'count,desc'} sort
 * @property {number} page  0부터
 *
 * 최근 발생 한 건. 서버 dto/RecentEvent
 * @typedef {object} RecentEvent
 * @property {string} occurredAt  시각 (ISO 문자열)
 * @property {string|null} message  마스킹된 메시지
 * @property {string[]} stack   '클래스.메서드(파일:줄)' 목록 (최대 30줄)
 * @property {string[]} causes  '예외타입: 메시지' 목록 (바깥 → 안쪽)
 * @property {string|null} thread
 * @property {'STARTUP'|'RUNTIME'|null} phase
 *
 * 에러 그룹 상세. 서버 dto/ErrorGroupDetail
 * @typedef {object} ErrorGroupDetail
 * @property {number} id
 * @property {string} exceptionType
 * @property {string|null} location
 * @property {'local'|'prod'} env
 * @property {'OPEN'|'RESOLVED'|'IGNORED'} status
 * @property {number} count
 * @property {string} firstSeen
 * @property {string} lastSeen
 * @property {string} fingerprint
 * @property {string|null} aiExplanation  서버가 저장한 글 그대로. 없으면 null
 * @property {string|null} aiExplainedAt
 * @property {'UP'|'DOWN'|null} feedback
 * @property {RecentEvent[]} recentEvents  최신순
 * @property {{date: string, count: number}[]} dailyCounts  오래된 날 → 오늘
 * @property {number} retentionDays  원본 보관 일수
 */

export const PAGE_SIZE = 20;

// 오늘에서 days일 전 날짜를 '2026-11-09' 모양으로. 서버는 날짜만 받으면 그날 0시(한국 시간)부터로 본다
function daysAgo(days) {
  const date = new Date();
  date.setDate(date.getDate() - days);
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * GET /api/teams/{teamId}/errors
 * @param {number} teamId
 * @param {ErrorGroupFilter} filter
 * @returns {Promise<ErrorGroupPage>}
 */
export function getErrorGroups(teamId, filter) {
  const params = new URLSearchParams();
  if (filter.env) {
    params.set("env", filter.env);
  }
  if (filter.status) {
    params.set("status", filter.status);
  }
  params.set("from", daysAgo(filter.days));
  params.set("sort", filter.sort);
  params.set("page", String(filter.page));
  params.set("size", String(PAGE_SIZE));
  return request("GET", `/api/teams/${teamId}/errors?${params.toString()}`);
}

/**
 * GET /api/teams/{teamId}/errors/{groupId}. 없거나 다른 팀 그룹이면 404
 * @param {number} teamId
 * @param {number} groupId
 * @returns {Promise<ErrorGroupDetail>}
 */
export function getErrorGroup(teamId, groupId) {
  return request("GET", `/api/teams/${teamId}/errors/${groupId}`);
}

/**
 * PATCH /api/teams/{teamId}/errors/{groupId}/status (AI-97). 바뀐 값만 돌려준다
 * 400 허용값 밖, 404 없거나 다른 팀 그룹
 * @param {number} teamId
 * @param {number} groupId
 * @param {'OPEN'|'RESOLVED'|'IGNORED'} status
 * @returns {Promise<{id: number, status: 'OPEN'|'RESOLVED'|'IGNORED'}>}
 */
export function changeErrorStatus(teamId, groupId, status) {
  return request("PATCH", `/api/teams/${teamId}/errors/${groupId}/status`, { status });
}

/**
 * PUT /api/teams/{teamId}/errors/{groupId}/feedback (AI-97). 같은 값을 다시 보내도 같은 결과
 * 400 허용값 밖, 404 없거나 다른 팀 그룹, 409 AI 설명이 아직 없는 그룹
 * @param {number} teamId
 * @param {number} groupId
 * @param {'UP'|'DOWN'} feedback
 * @returns {Promise<{id: number, feedback: 'UP'|'DOWN'}>}
 */
export function giveErrorFeedback(teamId, groupId, feedback) {
  return request("PUT", `/api/teams/${teamId}/errors/${groupId}/feedback`, { feedback });
}