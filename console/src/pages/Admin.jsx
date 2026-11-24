import { useState } from "react";
import { ForbiddenError } from "../api/client";
import { timeAgo } from "../app/format";
import { CohortTeamForm } from "../components/CohortTeamForm";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { MessageCard } from "../components/MessageCard";
import { OwnerInviteReveal } from "../components/OwnerInviteReveal";
import { TeamKeysDialog } from "../components/TeamKeysDialog";
import { useAdminOverview } from "../hooks/useAdminOverview";
import { useAdminTeamActions } from "../hooks/useAdminTeamActions";
import "../styles/settings.css";
import "../styles/admin.css";

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
  const { suspend, resume, retry, ownerInvite } = useAdminTeamActions();
  const [cohort, setCohort] = useState(""); // 빈 문자열이면 전체
  const [showCreate, setShowCreate] = useState(false); // 기수·팀 만들기 칸을 펼쳤는가
  const [notice, setNotice] = useState(null); // 방금 한 동작의 결과 { tone: "ok" | "error", text }
  const [suspending, setSuspending] = useState(null); // 정지 확인 중인 팀 줄
  const [invite, setInvite] = useState(null); // 조장 링크 원문 { teamName, inviteUrl, expiresAt } — 창이 열려 있는 동안만
  const [keysTeam, setKeysTeam] = useState(null); // 키 관리 창을 연 팀 줄

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

  // ── 팀별 동작. 결과는 표 위 안내 줄에 보여 준다. 서버가 거절하면 서버 message 를 그대로 보여 준다 ──
  const ok = (text) => setNotice({ tone: "ok", text });
  const fail = (error) => setNotice({ tone: "error", text: error.message });
  // 동작 하나가 끝나기 전에는 다른 동작을 못 누르게 한다 (같은 팀에 겹쳐 보내지 않으려고)
  const busy = suspend.isPending || resume.isPending || retry.isPending || ownerInvite.isPending;

  const giveOwnerLink = (row) => {
    setNotice(null);
    ownerInvite.mutate(row.teamId, {
      onSuccess: (result) =>
        setInvite({ teamName: row.name, inviteUrl: result.inviteUrl, expiresAt: result.expiresAt }),
      onError: fail,
    });
  };

  const closeInvite = () => {
    setInvite(null); // 원문을 상태에서 지운다
    ownerInvite.reset(); // 변경 결과(원문 포함)도 지운다
  };

  const confirmSuspend = () => {
    const row = suspending;
    suspend.mutate(row.teamId, {
      onSuccess: () => ok(`${row.name} 팀을 정지했어요. 다시 켜면 채널이 자동으로 다시 연결돼요.`),
      onError: fail,
      onSettled: () => setSuspending(null),
    });
  };

  const turnOn = (row) => {
    setNotice(null);
    resume.mutate(row.teamId, {
      onSuccess: (result) =>
        ok(
          result.status === "WAITING_OWNER"
            ? `${row.name} 팀을 다시 켰어요. 조장이 아직 없어서 조장 대기 상태예요.`
            : `${row.name} 팀을 다시 켰어요.`,
        ),
      onError: fail,
    });
  };

  const rebuildChannel = (row) => {
    setNotice(null);
    retry.mutate(row.teamId, {
      onSuccess: (result) => {
        if (result.result === "FAILED") {
          // 서버는 "단계:HTTP상태" 만 준다 (웹훅 주소·채널 id 는 없다)
          setNotice({
            tone: "error",
            text: `${row.name}: 채널을 만들지 못했어요 (${result.discordError ?? "원인 모름"}). 다시 시도할 수 있어요.`,
          });
        } else if (result.result === "ALREADY_READY") {
          ok(`${row.name}: 이미 준비돼 있어요.`);
        } else {
          ok(`${row.name}: 디스코드 채널을 만들었어요.`);
        }
      },
      onError: fail,
    });
  };

  return (
    <section className="stack">
      <div className="page-head">
        <h1 className="page-title">전체 현황</h1>
        <div className="head-side">
          <p className="muted">
            팀 {rows.length} · 연결됨 {connectedCount} · 30초마다 갱신
          </p>
          <button
            type="button"
            className="button primary compact"
            aria-expanded={showCreate}
            onClick={() => setShowCreate(!showCreate)}
          >
            {showCreate ? "만들기 닫기" : "기수·팀 만들기"}
          </button>
        </div>
      </div>

      {showCreate && <CohortTeamForm />}

      {notice && (
        <p role="status" className={notice.tone === "ok" ? "success-box" : "warning-box"}>
          {notice.text}
        </p>
      )}

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
                    <div className="cell-actions">
                      {row.status === "ACTIVE" && (
                        // 팀 에러 보기는 다음 작업에서 연결한다 (AI-107 API)
                        <button type="button" className="button compact" disabled>
                          에러 보기
                        </button>
                      )}
                      {row.status === "WAITING_OWNER" && (
                        <button
                          type="button"
                          className="button compact"
                          disabled={busy}
                          onClick={() => giveOwnerLink(row)}
                        >
                          조장 초대 링크
                        </button>
                      )}
                      {row.status === "SUSPENDED" && (
                        <button
                          type="button"
                          className="button compact"
                          disabled={busy}
                          onClick={() => turnOn(row)}
                        >
                          다시 켜기
                        </button>
                      )}
                      {row.discordStatus === "FAILED" && (
                        <button
                          type="button"
                          className="button compact"
                          disabled={busy}
                          onClick={() => rebuildChannel(row)}
                        >
                          {retry.isPending && retry.variables === row.teamId ? "만드는 중…" : "채널 다시 만들기"}
                        </button>
                      )}
                      <button type="button" className="button compact" onClick={() => setKeysTeam(row)}>
                        키 관리
                      </button>
                      {row.status !== "SUSPENDED" && (
                        <button
                          type="button"
                          className="button compact"
                          disabled={busy}
                          onClick={() => setSuspending(row)}
                        >
                          정지
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

      {suspending && (
        <ConfirmDialog
          title={`${suspending.name} 팀을 정지할까요?`}
          text="정지하면 이 팀의 수집이 멈추고 디스코드 웹훅이 삭제돼요. 다시 켜면 채널이 자동으로 다시 연결돼요."
          confirmLabel="정지"
          danger
          busy={suspend.isPending}
          onConfirm={confirmSuspend}
          onCancel={() => setSuspending(null)}
        />
      )}
      {invite && <OwnerInviteReveal teamName={invite.teamName} invite={invite} onClose={closeInvite} />}
      {keysTeam && <TeamKeysDialog team={keysTeam} onClose={() => setKeysTeam(null)} />}
    </section>
  );
}