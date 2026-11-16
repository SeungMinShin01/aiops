import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { bulkCreateTeams, createCohort } from "../api/admin";
import { queryKeys } from "../api/queryKeys";
import { queryClient } from "../app/queryClient";
import { useCohorts } from "../hooks/useCohorts";

// 줄 끝에 'YYYY-MM-DD' 가 붙어 있으면 프로젝트 종료일, 없으면 이름만
const END_DATE_AT_LINE_END = /^(.*?)[\s,]+(\d{4}-\d{2}-\d{2})$/;

// 한 줄에 팀 하나. "3기-1조" 또는 "3기-1조, 2027-02-20" → [{ name, projectEndDate }]. 빈 줄은 건너뛴다
// 이름·날짜가 올바른지는 서버가 검사한다 (400·409). 여기서는 줄만 나눈다
function parseTeamLines(text) {
  const teams = [];
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    if (line === "") {
      continue;
    }
    const found = line.match(END_DATE_AT_LINE_END);
    if (found && found[1].trim() !== "") {
      teams.push({ name: found[1].trim(), projectEndDate: found[2] });
    } else {
      teams.push({ name: line, projectEndDate: null });
    }
  }
  return teams;
}

// 기수 만들기 + 팀 일괄 생성 (관리자). 서버가 이름 겹침·개수(최대 50)·없는 기수를 검사하고, 이유는 message 로 돌려준다
export function CohortTeamForm() {
  const cohorts = useCohorts();
  const [cohortName, setCohortName] = useState("");
  const [startDate, setStartDate] = useState("");
  const [endDate, setEndDate] = useState("");
  const [cohortId, setCohortId] = useState(""); // 팀을 넣을 기수 (select 값이라 문자열)
  const [text, setText] = useState("");
  const [message, setMessage] = useState(null); // { tone: "ok" | "error", text }

  const refreshCohorts = () => queryClient.invalidateQueries({ queryKey: queryKeys.adminCohorts });

  const makeCohort = useMutation({
    mutationFn: (body) => createCohort(body),
    onSuccess: (created) => {
      setCohortId(String(created.cohortId)); // 방금 만든 기수를 바로 고른 상태로
      setCohortName("");
      setStartDate("");
      setEndDate("");
      setMessage({ tone: "ok", text: `기수 '${created.name}'를 만들었어요. 아래에 팀을 넣어 주세요.` });
      refreshCohorts();
    },
    onError: (error) => setMessage({ tone: "error", text: error.message }),
  });

  const makeTeams = useMutation({
    mutationFn: ({ id, teams }) => bulkCreateTeams(id, teams),
    onSuccess: (rows, variables) => {
      const asked = variables.teams.length;
      setText("");
      queryClient.invalidateQueries({ queryKey: queryKeys.adminOverview });
      refreshCohorts();
      if (rows.length === asked) {
        setMessage({ tone: "ok", text: `팀 ${rows.length}개를 만들었어요. 디스코드 채널은 잠시 뒤 자동으로 만들어져요.` });
      } else {
        // 검사는 통과했지만 일부 저장에 실패한 경우. 만든 팀은 현황 표에 있으니 없는 이름만 다시 넣게 안내한다
        setMessage({
          tone: "error",
          text: `팀 ${asked}개 중 ${rows.length}개만 만들었어요. 현황 표에 없는 팀 이름만 다시 넣어 주세요.`,
        });
      }
    },
    onError: (error) => setMessage({ tone: "error", text: error.message }),
  });

  const teams = parseTeamLines(text);

  const submitCohort = (event) => {
    event.preventDefault();
    setMessage(null);
    makeCohort.mutate({
      name: cohortName.trim(),
      startDate: startDate === "" ? null : startDate, // 비워 두면 null (서버는 빈 문자열을 날짜로 읽지 못한다)
      endDate: endDate === "" ? null : endDate,
    });
  };

  const submitTeams = (event) => {
    event.preventDefault();
    setMessage(null);
    makeTeams.mutate({ id: Number(cohortId), teams });
  };

  return (
    <section className="card admin-form" aria-labelledby="create-title">
      <h2 id="create-title" className="section-title">기수·팀 만들기</h2>

      <form className="inline-form" onSubmit={submitCohort}>
        <label className="grow">
          새 기수 이름
          <input type="text" value={cohortName} onChange={(event) => setCohortName(event.target.value)} />
        </label>
        <label>
          시작일 (선택)
          <input type="date" value={startDate} onChange={(event) => setStartDate(event.target.value)} />
        </label>
        <label>
          종료일 (선택)
          <input type="date" value={endDate} onChange={(event) => setEndDate(event.target.value)} />
        </label>
        <button
          type="submit"
          className="button small"
          disabled={cohortName.trim() === "" || makeCohort.isPending}
        >
          기수 만들기
        </button>
      </form>

      <form className="team-form" onSubmit={submitTeams}>
        <label className="field">
          기수
          <select value={cohortId} onChange={(event) => setCohortId(event.target.value)}>
            <option value="">{cohorts.data && cohorts.data.length === 0 ? "먼저 기수를 만드세요" : "기수를 고르세요"}</option>
            {(cohorts.data ?? []).map((cohort) => (
              <option key={cohort.cohortId} value={cohort.cohortId}>
                {cohort.name} · 팀 {cohort.teamCount}개
              </option>
            ))}
          </select>
        </label>
        <label className="textarea-label">
          팀 목록 (한 줄에 팀 하나, 이름 뒤에 프로젝트 종료일을 붙이면 돼요)
          <textarea
            rows={5}
            value={text}
            onChange={(event) => setText(event.target.value)}
            placeholder={"3기-1조, 2027-02-20\n3기-2조, 2027-02-20"}
          />
        </label>
        <div className="section-actions">
          <button
            type="submit"
            className="button primary small"
            disabled={cohortId === "" || teams.length === 0 || makeTeams.isPending}
          >
            {makeTeams.isPending ? "만드는 중…" : `팀 ${teams.length}개 만들기`}
          </button>
        </div>
      </form>

      {message && (
        <p role="status" className={message.tone === "ok" ? "success-box" : "warning-box"}>
          {message.text}
        </p>
      )}
    </section>
  );
}