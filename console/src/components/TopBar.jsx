import { useMutation } from "@tanstack/react-query";
import { Link, NavLink } from "react-router";
import { logout } from "../api/me";
import { queryClient } from "../app/queryClient";

// 검정 위쪽 메뉴. 관리자 탭은 isAdmin일 때만 보인다 (숨기기일 뿐, 권한은 서버가 판단)
// props: login(문자열), isAdmin(참·거짓), teamId(숫자, 없을 수 있음), onMenu(함수, 없을 수 있음)
export function TopBar({ login, isAdmin, teamId, onMenu }) {
  const signOut = useMutation({
    mutationFn: () => logout(),
    onSuccess: () => {
      queryClient.clear();
      window.location.assign("/login");
    },
  });

  return (
    <header className="topbar">
      {onMenu && (
        <button
          type="button"
          className="icon-button topbar-menu"
          aria-label="팀 목록 열기·닫기"
          onClick={() => onMenu()}
        >
          <svg
            aria-hidden="true"
            width="20"
            height="20"
            viewBox="0 0 20 20"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
          >
            <path d="M3 5.5h14M3 10h14M3 14.5h14" />
          </svg>
        </button>
      )}
      <Link to="/" className="topbar-logo">
        AIOps
      </Link>
      <nav className="topbar-nav" aria-label="주 메뉴">
        {teamId !== undefined && (
          <NavLink to={`/teams/${teamId}/errors`}>에러</NavLink>
        )}
        {teamId !== undefined && (
          <NavLink to={`/teams/${teamId}/settings`}>설정</NavLink>
        )}
        {isAdmin && <NavLink to="/admin">관리자</NavLink>}
      </nav>
      <span className="topbar-user">{login}</span>
      <button
        type="button"
        className="topbar-logout"
        onClick={() => signOut.mutate()}
        disabled={signOut.isPending}
      >
        로그아웃
      </button>
    </header>
  );
}
