import { http, HttpResponse } from "msw";
import { adminOverview } from "./adminData";

// 관리자 화면 가짜 응답 (AI-101, dev:mock 전용). 서버(13단계 컨트롤러)와 같은 주소·같은 응답 모양·같은 거절(400·404·409)을 흉내 낸다.
// 현황은 아래 rows 에 들고 있다가 정지·재개·채널·팀 생성에 따라 바꾼다 (새로고침하면 처음 모양으로 돌아간다)
// 관리자 키 발급·폐기·목록은 팀 설정 화면과 같은 keys 저장소를 쓰려고 handlers.js 에 있다

const rows = adminOverview();
const cohorts = [
  { cohortId: 1, name: "KDT 3기", startDate: "2026-09-01", endDate: "2027-02-20", teamCount: 5 },
  { cohortId: 2, name: "개인 프로젝트", startDate: null, endDate: null, teamCount: 1 },
];
let nextTeamId = 100;
let nextCohortId = 100;
let nextInviteId = 1;
const retryCount = {}; // 팀별 채널 재시도 횟수 — 첫 번째는 실패, 두 번째부터 성공하게 해서 두 경우를 다 볼 수 있다

const fail = (status, error, message) => HttpResponse.json({ error, message }, { status });
const findRow = (teamId) => rows.find((row) => row.teamId === Number(teamId));
const teamNotFound = () => fail(404, "TEAM_NOT_FOUND", "팀이 없어요");

export const adminHandlers = [
  // 현황 (AI-86)
  http.get("/api/admin/overview", () => HttpResponse.json(rows)),

  // 기수 (AI-82)
  http.get("/api/admin/cohorts", () => HttpResponse.json([...cohorts].reverse())),
  http.post("/api/admin/cohorts", async ({ request }) => {
    const { name, startDate, endDate } = await request.json();
    if (!name || name.trim() === "") {
      return fail(400, "COHORT_NAME_REQUIRED", "기수 이름이 필요합니다");
    }
    if (startDate && endDate && endDate < startDate) {
      return fail(400, "COHORT_DATE_INVALID", "종료일은 시작일보다 빠를 수 없습니다");
    }
    const created = {
      cohortId: nextCohortId++,
      name: name.trim(),
      startDate: startDate ?? null,
      endDate: endDate ?? null,
      teamCount: 0,
    };
    cohorts.push(created);
    return HttpResponse.json(created, { status: 201 });
  }),

  // 팀 일괄 생성 (AI-83). 이름이 겹치면 아무것도 만들지 않는다
  http.post("/api/admin/teams/bulk", async ({ request }) => {
    const { cohortId, teams } = await request.json();
    const cohort = cohorts.find((c) => c.cohortId === cohortId);
    if (!cohort) {
      return fail(404, "COHORT_NOT_FOUND", "기수를 찾을 수 없습니다");
    }
    if (!teams || teams.length < 1 || teams.length > 50) {
      return fail(400, "TEAMS_COUNT_INVALID", "팀은 1개 이상 50개 이하로 보내야 합니다");
    }
    const seen = new Set();
    for (const team of teams) {
      const name = (team.name ?? "").trim();
      if (name === "") {
        return fail(400, "TEAM_NAME_REQUIRED", "팀 이름이 필요합니다");
      }
      const exists = rows.some((row) => row.cohort === cohort.name && row.name === name);
      if (exists || seen.has(name)) {
        return fail(409, "TEAM_NAME_DUPLICATE", `이미 있는 팀 이름입니다: ${name}`);
      }
      seen.add(name);
    }
    const created = teams.map((team) => {
      const teamId = nextTeamId++;
      rows.push({
        teamId,
        name: team.name.trim(),
        cohort: cohort.name,
        status: "WAITING_OWNER",
        ownerLogin: null,
        lastSeenLocal: null,
        lastSeenProd: null,
        errors24h: 0,
        discordStatus: "NONE",
      });
      return {
        teamId,
        name: team.name.trim(),
        status: "WAITING_OWNER",
        discordStatus: "NONE",
        projectEndDate: team.projectEndDate ?? null,
        ownerLogin: null,
      };
    });
    cohort.teamCount += created.length;
    return HttpResponse.json(created, { status: 201 });
  }),

  // 조장 초대 링크 (AI-84). 조장 대기 팀만. 링크는 mock 초대 수락(owner 로 시작하는 토큰)과 이어진다
  http.post("/api/admin/teams/:teamId/owner-invite", ({ params }) => {
    const row = findRow(params.teamId);
    if (!row) {
      return teamNotFound();
    }
    if (row.status !== "WAITING_OWNER") {
      return fail(409, "TEAM_ALREADY_HAS_OWNER", "이미 조장이 있거나 정지된 팀입니다");
    }
    return HttpResponse.json(
      {
        inviteUrl: `${window.location.origin}/invite/owner-mock-${nextInviteId++}`,
        expiresAt: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      },
      { status: 201 },
    );
  }),

  // 정지·재개 (AI-85). 정지하면 웹훅이 삭제돼 디스코드 상태가 SUSPENDED, 재개하면 다시 READY
  http.post("/api/admin/teams/:teamId/suspend", ({ params }) => {
    const row = findRow(params.teamId);
    if (!row) {
      return teamNotFound();
    }
    row.status = "SUSPENDED";
    row.discordStatus = "SUSPENDED";
    return HttpResponse.json({ teamId: row.teamId, status: "SUSPENDED" });
  }),
  http.post("/api/admin/teams/:teamId/resume", ({ params }) => {
    const row = findRow(params.teamId);
    if (!row) {
      return teamNotFound();
    }
    row.status = row.ownerLogin ? "ACTIVE" : "WAITING_OWNER";
    row.discordStatus = "READY";
    return HttpResponse.json({ teamId: row.teamId, status: row.status });
  }),

  // 디스코드 채널 재시도 (AI-92). 수 초 걸리는 흉내로 0.8초 기다린다. 첫 시도는 FAILED, 다음부터 READY
  http.post("/api/admin/teams/:teamId/discord/retry", async ({ params }) => {
    const row = findRow(params.teamId);
    if (!row) {
      return teamNotFound();
    }
    if (row.status === "SUSPENDED") {
      return fail(409, "TEAM_SUSPENDED", "정지된 팀이에요. 재개하면 채널이 자동으로 다시 연결돼요");
    }
    await new Promise((resolve) => setTimeout(resolve, 800));
    if (row.discordStatus === "READY") {
      return HttpResponse.json({ teamId: row.teamId, result: "ALREADY_READY", discordStatus: "READY", discordError: null });
    }
    retryCount[row.teamId] = (retryCount[row.teamId] ?? 0) + 1;
    if (retryCount[row.teamId] === 1) {
      return HttpResponse.json({ teamId: row.teamId, result: "FAILED", discordStatus: "FAILED", discordError: "웹훅:HTTP 429" });
    }
    row.discordStatus = "READY";
    return HttpResponse.json({ teamId: row.teamId, result: "READY", discordStatus: "READY", discordError: null });
  }),
];