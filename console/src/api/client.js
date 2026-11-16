import axios from "axios";

// 모든 API 호출이 지나는 한 곳. 컴포넌트에서 axios를 직접 쓰지 않는다.
// 로그인 상태는 세션 쿠키뿐이다. 토큰을 localStorage에 두지 않는다 (D-020).

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status; // 0이면 서버에 닿지 못한 것 (네트워크 오류)
  }
}

export class ForbiddenError extends ApiError {}

const http = axios.create({
  withCredentials: true, // 세션 쿠키를 같이 보낸다
  headers: { Accept: "application/json" },
});

// 서버가 내려준 XSRF-TOKEN 쿠키를 읽어 X-XSRF-TOKEN 헤더로 되돌린다 (CSRF 방어).
// axios에도 같은 기능이 있지만, 동작이 여기서 보이도록 직접 붙인다
function csrfToken() {
  const found = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);
  return found ? decodeURIComponent(found[1]) : null;
}

/**
 * @param {'GET'|'POST'|'PUT'|'PATCH'|'DELETE'} method
 * @param {string} path  예: '/api/me'
 * @param {object} [body] 보낼 내용 (JSON으로 바뀐다)
 * @returns {Promise<any>} 응답 JSON. 204면 undefined
 */
export async function request(method, path, body) {
  const headers = {};
  if (method !== "GET") {
    const token = csrfToken();
    if (token) {
      headers["X-XSRF-TOKEN"] = token;
    }
  }

  try {
    const response = await http.request({
      method,
      url: path,
      data: body,
      headers,
    });
    return response.status === 204 ? undefined : response.data;
  } catch (error) {
    if (!axios.isAxiosError(error) || !error.response) {
      throw new ApiError(0, "서버에 연결하지 못했습니다");
    }
    const status = error.response.status;
    if (status === 401) {
      // 세션 없음·만료 → 로그인 화면으로. 보던 주소를 next로 넘겨 로그인 후 돌아오게 한다 (초대 링크 등, AI-106)
      const here = window.location.pathname;
      if (!here.startsWith("/login")) {
        const next = here + window.location.search;
        window.location.assign(
          next === "/" ? "/login" : `/login?next=${encodeURIComponent(next)}`,
        );
      }
      throw new ApiError(401, "로그인이 필요합니다");
    }
    if (status === 403) {
      // 권한 판단은 서버가 한다. 화면은 "권한 없음"을 그릴 뿐이다 (규칙 13)
      throw new ForbiddenError(403, "권한이 없습니다");
    }
    const data = error.response.data;
    const message =
      data && typeof data.message === "string"
        ? data.message
        : `요청에 실패했습니다 (${status})`;
    throw new ApiError(status, message);
  }
}
