// 가짜 관리자 현황 (팀·사람 모두 지어낸 것). 시각은 부를 때마다 "지금" 기준
function minutesAgo(minutes) {
  return new Date(Date.now() - minutes * 60000).toISOString();
}

export function adminOverview() {
  return [
    { teamId: 1, name: "3기-1조", cohort: "KDT 3기", status: "ACTIVE", ownerLogin: "dev-user",
      lastSeenLocal: minutesAgo(1), lastSeenProd: null, errors24h: 64, discordStatus: "READY" },
    { teamId: 4, name: "3기-2조", cohort: "KDT 3기", status: "ACTIVE", ownerLogin: "dev-kim",
      lastSeenLocal: minutesAgo(1), lastSeenProd: minutesAgo(2), errors24h: 12, discordStatus: "READY" },
    { teamId: 5, name: "3기-3조", cohort: "KDT 3기", status: "ACTIVE", ownerLogin: "lee-dev",
      lastSeenLocal: minutesAgo(120), lastSeenProd: null, errors24h: 3, discordStatus: "FAILED" },
    { teamId: 6, name: "3기-4조", cohort: "KDT 3기", status: "WAITING_OWNER", ownerLogin: null,
      lastSeenLocal: null, lastSeenProd: null, errors24h: 0, discordStatus: "READY" },
    { teamId: 7, name: "3기-5조", cohort: "KDT 3기", status: "SUSPENDED", ownerLogin: "park-j",
      lastSeenLocal: minutesAgo(60 * 24 * 3), lastSeenProd: null, errors24h: 0, discordStatus: "SUSPENDED" },
    { teamId: 3, name: "hong-쇼핑몰", cohort: "개인 프로젝트", status: "ACTIVE", ownerLogin: "hong",
      lastSeenLocal: minutesAgo(2), lastSeenProd: null, errors24h: 5, discordStatus: "READY" },
    { teamId: 2, name: "aiops-dogfood", cohort: null, status: "ACTIVE", ownerLogin: "captain",
      lastSeenLocal: minutesAgo(1), lastSeenProd: minutesAgo(1), errors24h: null, discordStatus: "NONE" },
  ];
}