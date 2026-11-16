import { envLabel, timeAgo } from "../app/format";

// env 하나의 연결 상태 배지 (AI-98 응답). 팀 설정 화면과 에러 목록 위쪽에서 같이 쓴다
// props: env('local'|'prod'), status({ state, lastSeenAt, hostCount }) — 아직 못 불러왔으면 undefined
const TEXT = {
  CONNECTED: "연결됨",
  NOT_CONNECTED: "연결 끊김",
  NEVER: "아직 신호 없음",
};

export function ConnectionBadge({ env, status }) {
  const state = status ? status.state : "LOADING";
  return (
    <div className={`conn-badge conn-${state.toLowerCase()}`}>
      <span className="conn-dot" aria-hidden="true" />
      <div className="conn-text">
        <strong>
          {envLabel(env)} · {status ? TEXT[state] : "확인 중…"}
        </strong>
        {status && status.lastSeenAt && (
          <span className="muted">
            마지막 신호: {timeAgo(status.lastSeenAt)}
            {status.state === "CONNECTED" && status.hostCount > 1 && ` · ${status.hostCount}대`}
          </span>
        )}
      </div>
    </div>
  );
}
