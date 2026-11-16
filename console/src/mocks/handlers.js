import { http, HttpResponse } from "msw";
import { errorDetail, errorGroups } from "./errorData";
import { adminOverview } from "./adminData";

// 가짜 응답 (dev:mock 전용). 팀 1은 조장(OWNER), 팀 2·3은 팀원(MEMBER) 세트.
// 팀원이 조장 전용 API를 부르면 서버처럼 403을 돌려준다 (화면이 "권한 없음"을 그리는지 보려고)

const OWNER_TEAMS = new Set([1]);
const minutesAgo = (m) => new Date(Date.now() - m * 60_000).toISOString();
const daysLater = (d) => new Date(Date.now() + d * 86_400_000).toISOString();

let nextId = 100;
const keys = {
  1: [
    {
      keyId: 1,
      env: "local",
      prefix: "aiops_l_ab12",
      createdAt: minutesAgo(60 * 24 * 3),
      revokedAt: null,
      lastUsedAt: minutesAgo(1),
    },
    {
      keyId: 2,
      env: "local",
      prefix: "aiops_l_zz09",
      createdAt: minutesAgo(60 * 24 * 9),
      revokedAt: minutesAgo(60 * 24 * 4),
      lastUsedAt: minutesAgo(60 * 24 * 5),
    },
  ],
  2: [
    {
      keyId: 3,
      env: "prod",
      prefix: "aiops_p_k3x1",
      createdAt: minutesAgo(60 * 24),
      revokedAt: null,
      lastUsedAt: null,
    },
  ],
  3: [],
};
const invites = {
  1: [
    {
      invitationId: 11,
      role: "MEMBER",
      usedCount: 2,
      maxUses: 10,
      expiresAt: daysLater(5),
      createdAt: minutesAgo(60 * 48),
    },
  ],
};
const members = {
  1: [
    {
      userId: 1,
      login: "dev-user",
      name: "개발자",
      role: "OWNER",
      joinedAt: minutesAgo(60 * 24 * 10),
    },
    {
      userId: 7,
      login: "bob",
      name: "밥",
      role: "MEMBER",
      joinedAt: minutesAgo(60 * 24 * 2),
    },
  ],
  2: [
    {
      userId: 9,
      login: "captain",
      name: null,
      role: "OWNER",
      joinedAt: minutesAgo(60 * 24 * 20),
    },
    {
      userId: 1,
      login: "dev-user",
      name: "개발자",
      role: "MEMBER",
      joinedAt: minutesAgo(60 * 24 * 7),
    },
  ],
  3: [
    {
      userId: 1,
      login: "dev-user",
      name: "개발자",
      role: "MEMBER",
      joinedAt: minutesAgo(60),
    },
  ],
};
const connections = {
  1: {
    local: { state: "CONNECTED", lastSeenAt: minutesAgo(0.5), hostCount: 2 },
    prod: { state: "NEVER", lastSeenAt: null, hostCount: 0 },
  },
  2: {
    local: { state: "NOT_CONNECTED", lastSeenAt: minutesAgo(42), hostCount: 0 },
    prod: { state: "CONNECTED", lastSeenAt: minutesAgo(1), hostCount: 1 },
  },
  3: {
    local: { state: "NEVER", lastSeenAt: null, hostCount: 0 },
    prod: { state: "NEVER", lastSeenAt: null, hostCount: 0 },
  },
};

const forbidden = () =>
  HttpResponse.json({ error: "forbidden" }, { status: 403 });
const ownerOnly = (teamId) => OWNER_TEAMS.has(Number(teamId));
const randomKeyBody = () =>
  Math.random().toString(36).slice(2, 10) +
  Math.random().toString(36).slice(2, 10);

// 상태·평가 바꾸기 흉내 (AI-97). errorGroups()·errorDetail()은 부를 때마다 새로 만들기 때문에
// 바뀐 값을 여기에 기억해 두었다가 목록·상세 응답에 덧씌운다 (화면이 바뀐 결과를 보게)
const changedStatus = new Map(); // groupId → 'OPEN' | 'RESOLVED' | 'IGNORED'
const changedFeedback = new Map(); // groupId → 'UP' | 'DOWN'
const STATUSES = ["OPEN", "RESOLVED", "IGNORED"];
const FEEDBACKS = ["UP", "DOWN"];

const withStatus = (group) =>
  changedStatus.has(group.id) ? { ...group, status: changedStatus.get(group.id) } : group;

// GET /api/teams/:teamId/errors 흉내. 서버(ErrorGroupController)와 같은 조건·같은 응답 모양
function listErrors(request) {
  const url = new URL(request.url);
  const env = url.searchParams.get("env");
  const status = url.searchParams.get("status");
  const from = url.searchParams.get("from");
  const sort = url.searchParams.get("sort") ?? "lastSeen,desc";
  const page = Number(url.searchParams.get("page") ?? 0);
  const size = Number(url.searchParams.get("size") ?? 20);
  const fromTime = from ? new Date(`${from}T00:00:00`).getTime() : 0;

  const filtered = errorGroups().map(withStatus).filter(
    (group) =>
      (!env || group.env === env) &&
      (!status || group.status === status) &&
      new Date(group.lastSeen).getTime() >= fromTime,
  );
  filtered.sort((a, b) =>
    sort.startsWith("count")
      ? b.count - a.count
      : new Date(b.lastSeen).getTime() - new Date(a.lastSeen).getTime(),
  );

  return HttpResponse.json({
    content: filtered.slice(page * size, page * size + size),
    totalElements: filtered.length,
    totalPages: Math.ceil(filtered.length / size),
    number: page,
    size,
  });
}


// GET /api/teams/:teamId/errors/:groupId 흉내. 없으면 서버처럼 404
function oneError(groupId) {
  const detail = errorDetail(Number(groupId));
  if (!detail) {
    return HttpResponse.json({ error: "group_not_found" }, { status: 404 });
  }
  return HttpResponse.json({
    ...withStatus(detail),
    feedback: changedFeedback.get(detail.id) ?? detail.feedback,
  });
}

// PATCH /api/teams/:teamId/errors/:groupId/status 흉내. 서버처럼 허용값 밖은 400, 없으면 404
async function changeStatus(params, request) {
  const groupId = Number(params.groupId);
  const { status } = await request.json();
  if (!errorDetail(groupId)) {
    return HttpResponse.json({ error: "group_not_found" }, { status: 404 });
  }
  if (!STATUSES.includes(status)) {
    return HttpResponse.json({ error: "invalid_request" }, { status: 400 });
  }
  changedStatus.set(groupId, status);
  return HttpResponse.json({ id: groupId, status });
}

// PUT /api/teams/:teamId/errors/:groupId/feedback 흉내. 설명이 없는 그룹은 서버처럼 409
async function giveFeedback(params, request) {
  const groupId = Number(params.groupId);
  const { feedback } = await request.json();
  const detail = errorDetail(groupId);
  if (!detail) {
    return HttpResponse.json({ error: "group_not_found" }, { status: 404 });
  }
  if (!FEEDBACKS.includes(feedback)) {
    return HttpResponse.json({ error: "invalid_request" }, { status: 400 });
  }
  if (!detail.aiExplanation) {
    return HttpResponse.json({ error: "no_explanation" }, { status: 409 });
  }
  changedFeedback.set(groupId, feedback);
  return HttpResponse.json({ id: groupId, feedback });
}

export const handlers = [
  http.get("/api/me", () =>
    HttpResponse.json({
      userId: 1,
      login: "dev-user",
      name: "개발자",
      isAdmin: true,
      discordConnected: false,
      teams: [
        { teamId: 1, name: "3기-1조", role: "OWNER", discordStatus: "READY" },
        {
          teamId: 2,
          name: "aiops-dogfood",
          role: "MEMBER",
          discordStatus: "FAILED",
        },
        {
          teamId: 3,
          name: "hong-쇼핑몰",
          role: "MEMBER",
          discordStatus: "NONE",
        },
      ],
    }),
  ),
  http.post("/api/logout", () => new HttpResponse(null, { status: 204 })),

  // 연결 상태 (AI-98)
  http.get("/api/teams/:teamId/connection", ({ params }) =>
    HttpResponse.json(connections[params.teamId] ?? connections[3]),
  ),

  // 키 (목록은 팀원 누구나, 발급·폐기는 관리자 — 이 가짜 사용자는 관리자)
  http.get("/api/teams/:teamId/keys", ({ params }) =>
    HttpResponse.json(keys[params.teamId] ?? []),
  ),
  http.post("/api/admin/teams/:teamId/keys", async ({ params, request }) => {
    const { env } = await request.json();
    const raw =
      `aiops_${env === "prod" ? "p" : "l"}_${randomKeyBody()}${randomKeyBody()}`.slice(
        0,
        40,
      );
    const keyId = nextId++;
    (keys[params.teamId] ??= []).unshift({
      keyId,
      env,
      prefix: raw.slice(0, 12),
      createdAt: new Date().toISOString(),
      revokedAt: null,
      lastUsedAt: null,
    });
    return HttpResponse.json({ keyId, env, key: raw }, { status: 201 });
  }),
  http.delete("/api/admin/teams/:teamId/keys/:keyId", ({ params }) => {
    const key = (keys[params.teamId] ?? []).find(
      (k) => k.keyId === Number(params.keyId),
    );
    if (key) {
      key.revokedAt = new Date().toISOString();
    }
    return new HttpResponse(null, { status: 204 });
  }),

  // 팀원 초대 링크 (조장만)
  http.get("/api/teams/:teamId/invitations", ({ params }) =>
    ownerOnly(params.teamId)
      ? HttpResponse.json(invites[params.teamId] ?? [])
      : forbidden(),
  ),
  http.post("/api/teams/:teamId/invitations", async ({ params, request }) => {
    if (!ownerOnly(params.teamId)) {
      return forbidden();
    }
    const { maxUses } = await request.json();
    const invitationId = nextId++;
    const expiresAt = daysLater(7);
    (invites[params.teamId] ??= []).unshift({
      invitationId,
      role: "MEMBER",
      usedCount: 0,
      maxUses,
      expiresAt,
      createdAt: new Date().toISOString(),
    });
    return HttpResponse.json(
      {
        invitationId,
        inviteUrl: `${window.location.origin}/invite/mock-${invitationId}`,
        expiresAt,
        maxUses,
      },
      { status: 201 },
    );
  }),
  http.delete("/api/teams/:teamId/invitations/:invitationId", ({ params }) => {
    if (!ownerOnly(params.teamId)) {
      return forbidden();
    }
    invites[params.teamId] = (invites[params.teamId] ?? []).filter(
      (i) => i.invitationId !== Number(params.invitationId),
    );
    return new HttpResponse(null, { status: 204 });
  }),

  // 팀원 (목록은 누구나, 내보내기는 조장만)
  http.get("/api/teams/:teamId/members", ({ params }) =>
    HttpResponse.json(members[params.teamId] ?? []),
  ),
  http.delete("/api/teams/:teamId/members/:userId", ({ params }) => {
    if (!ownerOnly(params.teamId)) {
      return forbidden();
    }
    members[params.teamId] = (members[params.teamId] ?? []).filter(
      (m) => m.userId !== Number(params.userId),
    );
    return new HttpResponse(null, { status: 204 });
  }),

  // 초대 수락 (AI-105). 토큰이 expired로 시작하면 410, owner로 시작하면 조장 합류
  http.post("/api/invitations/:token/accept", ({ params }) => {
    if (params.token.startsWith("expired")) {
      return HttpResponse.json({ error: "INVITATION_GONE" }, { status: 410 });
    }
    const owner = params.token.startsWith("owner");
    return HttpResponse.json({
      teamId: 1,
      teamName: "3기-1조",
      role: owner ? "OWNER" : "MEMBER",
      next: owner ? "key-guide" : "errors",
    });
  }),

  // 에러 목록 (AI-95)
  http.get("/api/teams/:teamId/errors", ({ request }) => listErrors(request)),
  
  // 에러 상세 (AI-96)
  http.get("/api/teams/:teamId/errors/:groupId", ({ params }) => oneError(params.groupId)),

  // 상태 변경·AI 설명 평가 (AI-97, 연결은 AI-100)
  http.patch("/api/teams/:teamId/errors/:groupId/status", ({ params, request }) =>
    changeStatus(params, request),
  ),
  http.put("/api/teams/:teamId/errors/:groupId/feedback", ({ params, request }) =>
    giveFeedback(params, request),
  ),

  // 관리자 전체 현황 (AI-86)
  http.get("/api/admin/overview", () => HttpResponse.json(adminOverview())),
];