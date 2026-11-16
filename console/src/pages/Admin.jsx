import { useState } from "react";
import { ForbiddenError } from "../api/client";
import { timeAgo } from "../app/format";
import { MessageCard } from "../components/MessageCard";
import { useAdminOverview } from "../hooks/useAdminOverview";

// 마지막 신호가 이 시간 안이면 "연결됨". 서버 설정 aiops.console.connected-within(3분)과 같은 값.
// 서버는 시각만 주고 판정은 콘솔이 한다 (OverviewRow 주석)
const CONNECTED_WITHIN_MS = 3 * 60 * 1000;

const STATUS_LABEL = { WAITING_OWNER: "조장 대기", ACTIVE: "활성", SUSPENDED: "정지" };
const STATUS_CLASS = { WAITING_OWNER: "open", ACTIVE: "resolved", SUSPENDED: "ignored" };
const DISCORD_LABEL = { NONE: "아직", READY: "준비됨", FAILED: "실패", SUSPENDED: "웹훅 삭제됨" };

function isConnected(lastSeen, now) {
  return lastSeen !== null && now - new Date(lastSeen).getTime() <= CONNECTED_WITHIN_MS;
}

function connectionText(lastSeen, now) {
  if (lastSeen === null) {
    return "한 번도 안 옴";
  }
  return isConnected(lastSeen, now) ? "연결됨" : `끊김 · ${timeAgo(lastSeen, now)}`;
}

export function Admin() {
  const overview = useAdminOverview();
  const [cohort, setCohort] = useState(""); // 빈 문자열이면 전체

  if (overview.isPending) {
    return <p className="page-message">불러오는 중…</p>;
  }
  if (overview.isError) {
    const forbidden = overview.error instanceof ForbiddenError;
    return (
      <MessageCard
        title={forbidden ? "관리자만 볼 수 있습니다" : "불러오지 못했어요"}
        text={forbidden ? "이 화면은 관리자 계정으로만 열립니다." : overview.error.message}
      />
    );
  }

  const rows = overview.data;
  // 받은 시각을 기준으로 삼는다 (그리는 도중에 시계를 다시 읽지 않는다)
  const now = overview.dataUpdatedAt;

  // 기수별 팀 수. 기수가 없는 팀은 "기수 없음"으로 묶는다
  const counts = new Map();
  for (const row of rows) {
    const name = row.cohort ?? "기수 없음";
    counts.set(name, (counts.get(name) ?? 0) + 1);
  }
  const shown = cohort === "" ? rows : rows.filter((row) => (row.cohort ?? "기수 없음") === cohort);
  const connectedCount = rows.filter(
    (row) => isConnected(row.lastSeenLocal, now) || isConnected(row.lastSeenProd, now),
  ).length;

  return (
    <section className="stack">
      <div className="page-head">
        <h1 className="page-title">전체 현황</h1>
        <p className="muted">
          팀 {rows.length} · 연결됨 {connectedCount} · 30초마다 갱신
        </p>
      </div>

      <div className="filters">
        <div className="segmented" role="group" aria-label="기수">
          <button
            type="button"
            className={cohort === "" ? "active" : undefined}
            aria-pressed={cohort === ""}
            onClick={() => setCohort("")}
          >
            전체 {rows.length}
          </button>
          {[...counts.entries()].map(([name, count]) => (
            <button
              key={name}
              type="button"
              className={cohort === name ? "active" : undefined}
              aria-pressed={cohort === name}
              onClick={() => setCohort(name)}
            >
              {name} {count}
            </button>
          ))}
        </div>
      </div>

      {shown.length === 0 ? (
        <p className="page-message">팀이 없습니다.</p>
      ) : (
        <div className="table-card">
          <table className="data-table admin-table">
            <thead>
              <tr>
                <th scope="col">팀</th>
                <th scope="col">조장</th>
                <th scope="col">상태</th>
                <th scope="col">로컬</th>
                <th scope="col">운영</th>
                <th scope="col" className="num">
                  24시간 에러
                </th>
                <th scope="col">디스코드</th>
                <th scope="col">할 일</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((row) => (
                <tr key={row.teamId}>
                  <td>
                    <div className="strong">{row.name}</div>
                    <div className="row-sub">{row.cohort ?? "기수 없음"}</div>
                  </td>
                  <td className={row.ownerLogin ? undefined : "muted-cell"}>
                    {row.ownerLogin ?? "아직 없음"}
                  </td>
                  <td>
                    <span className={`pill ${STATUS_CLASS[row.status] ?? "ignored"}`}>
                      {STATUS_LABEL[row.status] ?? row.status}
                    </span>
                  </td>
                  <td className={isConnected(row.lastSeenLocal, now) ? undefined : "muted-cell"}>
                    {connectionText(row.lastSeenLocal, now)}
                  </td>
                  <td className={isConnected(row.lastSeenProd, now) ? undefined : "muted-cell"}>
                    {connectionText(row.lastSeenProd, now)}
                  </td>
                  <td className="num strong">{row.errors24h ?? "—"}</td>
                  <td>
                    {row.discordStatus === "FAILED" ? (
                      <span className="pill open">실패</span>
                    ) : (
                      (DISCORD_LABEL[row.discordStatus] ?? row.discordStatus)
                    )}
                  </td>
                  <td>
                    {/* 모양만. 누르는 동작은 AI-101에서 연결한다 */}
                    <div className="cell-actions">
                      {row.status === "ACTIVE" && (
                        <button type="button" className="button compact" disabled>
                          에러 보기
                        </button>
                      )}
                      {row.status === "WAITING_OWNER" && (
                        <button type="button" className="button compact" disabled>
                          조장 초대 링크
                        </button>
                      )}
                      {row.status === "SUSPENDED" && (
                        <button type="button" className="button compact" disabled>
                          다시 켜기
                        </button>
                      )}
                      {row.discordStatus === "FAILED" && (
                        <button type="button" className="button compact" disabled>
                          채널 다시 만들기
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}