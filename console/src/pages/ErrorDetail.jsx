import { Link, Navigate, useParams } from "react-router";
import { ApiError, ForbiddenError } from "../api/client";
import {
  ENV_LABEL,
  PHASE_LABEL,
  STATUS_LABEL,
  formatDateTime,
  formatWhen,
  shortType,
  splitExplanation,
  splitLocation,
  teamPrefix,
} from "../app/format";
import { MessageCard } from "../components/MessageCard";
import { useErrorGroup } from "../hooks/useErrorGroup";

const STACK_PREVIEW = 8; // 처음에 보여 주는 스택 줄 수

export function ErrorDetail() {
  const params = useParams();
  const teamId = Number(params.teamId);
  const groupId = Number(params.groupId);
  const group = useErrorGroup(teamId, groupId);
  const backTo = `/teams/${teamId}/errors`;

  if (group.isPending) {
    return <p className="page-message">불러오는 중…</p>;
  }
  if (group.isError) {
    if (group.error instanceof ForbiddenError) {
      return <Navigate to="/forbidden" replace />;
    }
    const notFound = group.error instanceof ApiError && group.error.status === 404;
    return (
      <MessageCard
        title={notFound ? "에러를 찾을 수 없어요" : "불러오지 못했어요"}
        text={
          notFound
            ? "없는 에러이거나 이 팀의 에러가 아닙니다."
            : group.error.message
        }
      >
        <Link className="button" to={backTo}>
          에러 목록으로
        </Link>
      </MessageCard>
    );
  }

  const detail = group.data;
  const latest = detail.recentEvents[0]; // 가장 최근 발생 (없을 수 있다: 원본 보관 기간이 지남)
  const where = splitLocation(detail.location);
  const sections = splitExplanation(detail.aiExplanation);
  const prefix = teamPrefix(detail.location);
  const stack = latest ? latest.stack : [];
  const maxCount = Math.max(1, ...detail.dailyCounts.map((day) => day.count));

  return (
    <article className="stack">
      <Link className="back-link" to={backTo}>
        ← 에러 목록
      </Link>

      <header className="detail-head">
        <div className="detail-tags">
          <span className={`pill ${detail.status.toLowerCase()}`}>
            {STATUS_LABEL[detail.status] ?? detail.status}
          </span>
          <span className={detail.env === "prod" ? "env-tag dark" : "env-tag"}>
            {ENV_LABEL[detail.env] ?? detail.env}
          </span>
          {latest && latest.phase && (
            <span className="env-tag">{PHASE_LABEL[latest.phase] ?? latest.phase}</span>
          )}
        </div>
        <h1 className="detail-title">{shortType(detail.exceptionType)}</h1>
        {where ? (
          <div>
            <div className="detail-where">
              <code>{where.className}</code> 클래스의 <code>{where.methodName}</code>{" "}
              메서드
            </div>
            <div className="detail-package">{where.packageName}</div>
          </div>
        ) : (
          <div className="detail-where">{detail.location ?? "위치를 알 수 없음"}</div>
        )}
        {latest && latest.message && (
          <p className="detail-message">{latest.message}</p>
        )}
      </header>

      <dl className="stat-row">
        <div className="stat">
          <dt>횟수</dt>
          <dd className="stat-big">{detail.count}</dd>
        </div>
        <div className="stat">
          <dt>처음</dt>
          <dd>{formatDateTime(detail.firstSeen)}</dd>
        </div>
        <div className="stat">
          <dt>마지막</dt>
          <dd>{formatWhen(detail.lastSeen)}</dd>
        </div>
      </dl>

      {/* 모양만. 누르면 상태를 바꾸는 연결은 AI-100에서 한다 */}
      <div className="action-row">
        <button type="button" className="button primary" disabled>
          해결로 표시
        </button>
        <button type="button" className="button" disabled>
          무시
        </button>
      </div>

      <section className="ai-card" aria-labelledby="ai-title">
        <div className="block-head">
          <h2 id="ai-title" className="block-title accent">
            AI 설명
          </h2>
          <span className="block-note">그룹당 1회 생성 · 추정입니다</span>
        </div>
        {!detail.aiExplanation && (
          <p className="muted">설명을 준비하고 있어요. 잠시 뒤 새로고침해 주세요.</p>
        )}
        {detail.aiExplanation && sections && (
          <div className="ai-columns">
            {sections.map((section) => (
              <div key={section.title}>
                <h3 className="ai-heading">{section.title}</h3>
                <p className="ai-body">{section.body}</p>
              </div>
            ))}
          </div>
        )}
        {detail.aiExplanation && !sections && (
          <p className="ai-body">{detail.aiExplanation}</p>
        )}
        {detail.aiExplanation && (
          <div className="feedback-row">
            <span className="muted">도움이 됐나요?</span>
            {/* 모양만. 연결은 AI-100 */}
            <button
              type="button"
              className={detail.feedback === "UP" ? "button chosen" : "button"}
              disabled
            >
              도움 됨
            </button>
            <button
              type="button"
              className={detail.feedback === "DOWN" ? "button chosen" : "button"}
              disabled
            >
              아님
            </button>
          </div>
        )}
      </section>

      <section aria-labelledby="stack-title">
        <div className="block-head">
          <h2 id="stack-title" className="block-title">
            스택
          </h2>
          <span className="block-note">우리 팀 코드 강조</span>
        </div>
        {stack.length === 0 ? (
          <p className="muted">
            원본이 남아 있지 않습니다 (보관 {detail.retentionDays}일).
          </p>
        ) : (
          <div className="stack-box">
            <div className="stack-lines">
              {stack.slice(0, STACK_PREVIEW).map((line, index) => (
                <StackLine key={index} line={line} prefix={prefix} />
              ))}
              {stack.length > STACK_PREVIEW && (
                <details>
                  <summary>… 프레임 {stack.length - STACK_PREVIEW}개 더</summary>
                  {stack.slice(STACK_PREVIEW).map((line, index) => (
                    <StackLine key={index} line={line} prefix={prefix} />
                  ))}
                </details>
              )}
            </div>
          </div>
        )}
      </section>

      {latest && latest.causes.length > 0 && (
        <section aria-labelledby="cause-title">
          <h2 id="cause-title" className="block-title">
            원인 체인
          </h2>
          <ol className="cause-list">
            {latest.causes.map((cause, index) => (
              <li key={index}>{cause}</li>
            ))}
          </ol>
        </section>
      )}

      <section aria-labelledby="daily-title">
        <div className="block-head">
          <h2 id="daily-title" className="block-title">
            최근 {detail.dailyCounts.length}일
          </h2>
          <span className="block-note">원본 보관 {detail.retentionDays}일</span>
        </div>
        <ol className="bars" aria-label="일별 발생 횟수">
          {detail.dailyCounts.map((day) => (
            <li key={day.date} title={`${day.date} · ${day.count}회`}>
              <span
                className="bar"
                style={{ height: `${Math.round((day.count / maxCount) * 100)}%` }}
              />
              <span className="sr-only">
                {day.date} {day.count}회
              </span>
            </li>
          ))}
        </ol>
      </section>

      <section aria-labelledby="recent-title">
        <h2 id="recent-title" className="block-title">
          최근 발생
        </h2>
        {detail.recentEvents.length === 0 ? (
          <p className="muted">원본이 남아 있지 않습니다.</p>
        ) : (
          <ul className="recent-list">
            {detail.recentEvents.map((event, index) => (
              <li key={index}>
                <span>{formatDateTime(event.occurredAt)}</span>
                <span className="recent-thread">{event.thread ?? ""}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </article>
  );
}

// 스택 한 줄. 우리 팀 코드(앞머리가 같은 줄)는 밝게, 나머지는 흐리게
function StackLine({ line, prefix }) {
  const mine = prefix !== null && line.startsWith(prefix);
  return <div className={mine ? "stack-line mine" : "stack-line"}>at {line}</div>;
}