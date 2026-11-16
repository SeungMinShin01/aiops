import { http, HttpResponse } from "msw";
import { errorGroups } from "./errorData";

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

  const filtered = errorGroups().filter(
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

export const handlers = [
  http.get("/api/me", () =>
    HttpResponse.json({
      userId: 1,
      login: "dev-user",
      name: "개발자",
      isAdmin: true,
      teams: [
        { teamId: 1, name: "3기-1조", role: "OWNER" },
        { teamId: 2, name: "aiops-dogfood", role: "MEMBER" },
        { teamId: 3, name: "hong-쇼핑몰", role: "MEMBER" },
      ],
    }),
  ),
  http.post("/api/logout", () => new HttpResponse(null, { status: 204 })),
  http.get("/api/teams/:teamId/errors", ({ request }) => listErrors(request)),
];
