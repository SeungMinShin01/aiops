import { request } from "./client";

// 팀 설정 화면이 쓰는 서버 API (12·14단계, AI-88·89·105). 응답 모양은 서버 dto와 같다.
// 권한은 서버가 판단한다. 조장이 아니면 쓰기 API는 403 → client.js가 ForbiddenError로 던진다

/**
 * POST /api/invitations/{token}/accept — 초대 수락 (AI-105)
 * @typedef {object} AcceptResponse
 * @property {number} teamId
 * @property {string} teamName
 * @property {'OWNER'|'MEMBER'} role
 * @property {'key-guide'|'errors'} next  조장이면 key-guide(설정 화면 안내), 팀원이면 errors
 * @returns {Promise<AcceptResponse>}  404 없는 링크, 410 만료·횟수 초과
 */
export function acceptInvite(token) {
  return request("POST", `/api/invitations/${encodeURIComponent(token)}/accept`);
}

/**
 * GET /api/teams/{teamId}/keys — 팀 키 목록 (팀원 누구나). 원문·해시는 없다 (AI-88)
 * @typedef {object} ApiKeySummary
 * @property {number} keyId
 * @property {'local'|'prod'} env
 * @property {string} prefix       앞 12자 (예: aiops_l_ab12)
 * @property {string} createdAt
 * @property {string|null} revokedAt
 * @property {string|null} lastUsedAt
 * @returns {Promise<ApiKeySummary[]>}
 */
export function listKeys(teamId) {
  return request("GET", `/api/teams/${teamId}/keys`);
}

/**
 * POST /api/admin/teams/{teamId}/keys — 키 발급 (관리자만, D-092). 원문은 이 응답에서 한 번만 나온다
 * @typedef {object} KeyIssued
 * @property {number} keyId
 * @property {'local'|'prod'} env
 * @property {string} key  원문. 화면 상태·저장소·URL·로그에 남기지 않는다
 * @returns {Promise<KeyIssued>}
 */
export function issueKey(teamId, env) {
  return request("POST", `/api/admin/teams/${teamId}/keys`, { env });
}

/** DELETE /api/admin/teams/{teamId}/keys/{keyId} — 키 폐기 (관리자만). 204 */
export function revokeKey(teamId, keyId) {
  return request("DELETE", `/api/admin/teams/${teamId}/keys/${keyId}`);
}

/**
 * GET /api/teams/{teamId}/invitations — 활성 팀원 초대 링크 (조장만, AI-89). 원문 링크는 없다
 * @typedef {object} InviteSummary
 * @property {number} invitationId
 * @property {string} role
 * @property {number} usedCount
 * @property {number} maxUses
 * @property {string} expiresAt
 * @returns {Promise<InviteSummary[]>}
 */
export function listInvites(teamId) {
  return request("GET", `/api/teams/${teamId}/invitations`);
}

/**
 * POST /api/teams/{teamId}/invitations — 팀원 초대 링크 만들기 (조장만). maxUses 1~50
 * @typedef {object} InviteCreated
 * @property {number} invitationId
 * @property {string} inviteUrl  이 응답에서만 나온다
 * @property {string} expiresAt
 * @property {number} maxUses
 * @returns {Promise<InviteCreated>}
 */
export function createInvite(teamId, maxUses) {
  return request("POST", `/api/teams/${teamId}/invitations`, { maxUses });
}

/** DELETE /api/teams/{teamId}/invitations/{invitationId} — 링크 만료시키기 (조장만). 204 */
export function expireInvite(teamId, invitationId) {
  return request("DELETE", `/api/teams/${teamId}/invitations/${invitationId}`);
}

/**
 * GET /api/teams/{teamId}/members — 팀원 목록 (팀원 누구나)
 * @typedef {object} MemberSummary
 * @property {number} userId
 * @property {string} login
 * @property {string|null} name
 * @property {'OWNER'|'MEMBER'} role
 * @property {string} joinedAt
 * @returns {Promise<MemberSummary[]>}
 */
export function listMembers(teamId) {
  return request("GET", `/api/teams/${teamId}/members`);
}

/** DELETE /api/teams/{teamId}/members/{userId} — 팀원 내보내기 (조장만, 자기 자신 불가). 204 */
export function removeMember(teamId, userId) {
  return request("DELETE", `/api/teams/${teamId}/members/${userId}`);
}
