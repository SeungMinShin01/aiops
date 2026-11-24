import { NavLink } from "react-router";

const ROLE_LABEL = { OWNER: "조장", MEMBER: "팀원" };

// 왼쪽 팀 목록. 오른쪽 위 버튼으로 접고 편다
// props: teams(TeamItem 배열), collapsed(참·거짓), onToggle(함수)
export function TeamSidebar({ teams, collapsed, onToggle }) {
  return (
    <aside
      className={collapsed ? "sidebar collapsed" : "sidebar"}
      aria-label="내 팀"
    >
      <div className="sidebar-head">
        <span className="sidebar-title">내 팀</span>
        <button
          type="button"
          className="icon-button"
          aria-label={collapsed ? "팀 목록 펼치기" : "팀 목록 접기"}
          aria-expanded={!collapsed}
          onClick={() => onToggle()}
        >
          <svg
            aria-hidden="true"
            className="sidebar-chevron"
            width="18"
            height="18"
            viewBox="0 0 18 18"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
          >
            <path d="M10.5 4.5 6 9l4.5 4.5" />
            <path d="M14 4.5 9.5 9l4.5 4.5" />
          </svg>
        </button>
      </div>
      <ul className="team-list">
        {teams.map((team) => (
          <li key={team.teamId}>
            <NavLink
              to={`/teams/${team.teamId}/errors`}
              className="team-link"
              title={team.name}
            >
              <span className="team-badge" aria-hidden="true">
                {team.name.slice(0, 2)}
              </span>
              <span className="team-text">
                <span className="team-name">{team.name}</span>
                <span className="team-role">
                  {ROLE_LABEL[team.role] ?? team.role}
                </span>
              </span>
            </NavLink>
          </li>
        ))}
      </ul>
    </aside>
  );
}
