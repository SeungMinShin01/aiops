import { http, HttpResponse } from "msw";

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
];
