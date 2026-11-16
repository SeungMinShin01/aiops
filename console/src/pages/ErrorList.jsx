import { Link, Navigate, useParams, useSearchParams } from "react-router";
import { ForbiddenError } from "../api/client";
import { PAGE_SIZE } from "../api/errors";
import {
  ENV_LABEL,
  STATUS_LABEL,
  formatWhen,
  locationText,
  shortType,
} from "../app/format";
import { useErrorGroups } from "../hooks/useErrorGroups";

const ENV_OPTIONS = [
  ["", "전체"],
  ["local", "로컬"],
  ["prod", "운영"],
];
const STATUS_OPTIONS = [
  ["OPEN", "열림"],
  ["RESOLVED", "해결"],
  ["IGNORED", "무시"],
  ["ALL", "전체"],
];
const DAYS_OPTIONS = [
  [1, "최근 1일"],
  [7, "최근 7일"],
  [30, "최근 30일"],
];
const SORT_OPTIONS = [
  ["lastSeen,desc", "최근 발생 순"],
  ["count,desc", "횟수 많은 순"],
];

// 조건은 주소(?env=local&status=OPEN…)에 둔다 → 상세를 보고 뒤로 와도 조건이 남고, 주소를 그대로 공유할 수 있다.
// 주소는 사람이 고칠 수 있으므로 허용값이 아니면 기본값으로 바꾼다
function readFilter(search) {
  const env = search.get("env");
  const status = search.get("status");
  const days = Number(search.get("days"));
  const sort = search.get("sort");
  const page = Number(search.get("page"));

  let statusValue = "OPEN"; // 처음 들어오면 열린 것만
  if (status === "ALL") {
    statusValue = "";
  } else if (status === "RESOLVED" || status === "IGNORED") {
    statusValue = status;
  }

  return {
    env: env === "local" || env === "prod" ? env : "",
    status: statusValue,
    days: days === 1 || days === 30 ? days : 7,
    sort: sort === "count,desc" ? sort : "lastSeen,desc",
    page: Number.isInteger(page) && page > 0 ? page : 0,
  };
}

export function ErrorList() {
  const params = useParams();
  const teamId = Number(params.teamId);
  const [search, setSearch] = useSearchParams();
  const filter = readFilter(search);
  const groups = useErrorGroups(teamId, filter);

  // 조건 하나를 바꾼다. 쪽 번호가 아닌 조건을 바꾸면 첫 쪽으로 돌아간다
  const change = (name, value) => {
    const next = new URLSearchParams(search);
    next.set(name, String(value));
    if (name !== "page") {
      next.delete("page");
    }
    setSearch(next, { replace: true });
  };

  if (groups.isError && groups.error instanceof ForbiddenError) {
    return <Navigate to="/forbidden" replace />;
  }

  const page = groups.data;
  const items = page ? page.content : [];
  const first = page ? page.number * page.size + 1 : 0;
  const last = page ? first + items.length - 1 : 0;

  return (
    <section className="stack">
      <div className="page-head">
        <h1 className="page-title">에러</h1>
        {page && (
          <p className="muted">조건에 맞는 에러 {page.totalElements}건</p>
        )}
      </div>

      <div className="filters">
        <div className="segmented" role="group" aria-label="환경">
          {ENV_OPTIONS.map(([value, label]) => (
            <button
              key={value}
              type="button"
              className={filter.env === value ? "active" : undefined}
              aria-pressed={filter.env === value}
              onClick={() => change("env", value)}
            >
              {label}
            </button>
          ))}
        </div>
        <label className="field">
          상태
          <select
            value={filter.status || "ALL"}
            onChange={(event) => change("status", event.target.value)}
          >
            {STATUS_OPTIONS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          기간
          <select
            value={filter.days}
            onChange={(event) => change("days", event.target.value)}
          >
            {DAYS_OPTIONS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="field field-end">
          정렬
          <select
            value={filter.sort}
            onChange={(event) => change("sort", event.target.value)}
          >
            {SORT_OPTIONS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      </div>

      {groups.isPending && <p className="page-message">불러오는 중…</p>}
      {groups.isError && (
        <p className="page-message" role="alert">
          {groups.error.message}
        </p>
      )}
      {page && items.length === 0 && (
        <p className="page-message">조건에 맞는 에러가 없습니다.</p>
      )}

      {items.length > 0 && (
        <>
          {/* 넓은 화면: 표 */}
          <div className="table-card wide-only">
            <table className="data-table error-table">
              <thead>
                <tr>
                  <th scope="col">에러</th>
                  <th scope="col">환경</th>
                  <th scope="col" className="num">
                    횟수
                  </th>
                  <th scope="col">마지막 발생</th>
                  <th scope="col">상태</th>
                </tr>
              </thead>
              <tbody>
                {items.map((group) => (
                  <tr key={group.id}>
                    <td>
                      <Link
                        className="row-title"
                        to={`/teams/${teamId}/errors/${group.id}`}
                      >
                        {shortType(group.exceptionType)}
                      </Link>
                      <div className="row-sub">
                        {locationText(group.location)}
                      </div>
                      <div className="row-sub">
                        {group.hasExplanation
                          ? "AI 설명 있음"
                          : "AI 설명 준비 중"}
                      </div>
                    </td>
                    <td>
                      <EnvTag env={group.env} />
                    </td>
                    <td className="num strong">{group.count}</td>
                    <td className="muted-cell">{formatWhen(group.lastSeen)}</td>
                    <td>
                      <StatusPill status={group.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 좁은 화면: 카드 */}
          <ul className="card-list narrow-only">
            {items.map((group) => (
              <li key={group.id}>
                <Link
                  className="error-card"
                  to={`/teams/${teamId}/errors/${group.id}`}
                >
                  <span className="card-top">
                    <StatusPill status={group.status} />
                    <EnvTag env={group.env} />
                    <span className="card-when">
                      {formatWhen(group.lastSeen)}
                    </span>
                  </span>
                  <span className="row-title">
                    {shortType(group.exceptionType)}
                  </span>
                  <span className="row-sub">
                    {locationText(group.location)}
                  </span>
                  <span className="row-sub">
                    {group.count}회 ·{" "}
                    {group.hasExplanation ? "AI 설명 있음" : "AI 설명 준비 중"}
                  </span>
                </Link>
              </li>
            ))}
          </ul>

          <div className="pager">
            <span>
              {first}–{last} / {page.totalElements}
            </span>
            <div className="pager-buttons">
              <button
                type="button"
                className="button"
                disabled={page.number === 0}
                onClick={() => change("page", page.number - 1)}
              >
                이전
              </button>
              <button
                type="button"
                className="button"
                disabled={(page.number + 1) * PAGE_SIZE >= page.totalElements}
                onClick={() => change("page", page.number + 1)}
              >
                다음
              </button>
            </div>
          </div>
        </>
      )}
    </section>
  );
}

function EnvTag({ env }) {
  return (
    <span className={env === "prod" ? "tag dark" : "tag"}>
      {ENV_LABEL[env] ?? env}
    </span>
  );
}

function StatusPill({ status }) {
  return (
    <span className={`pill ${status.toLowerCase()}`}>
      {STATUS_LABEL[status] ?? status}
    </span>
  );
}
