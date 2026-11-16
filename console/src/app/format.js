// 화면 표기 도우미. 서버 값(local·prod, OPEN, ISO 시각)을 사람이 읽는 말로 바꾼다.
// 서버 값은 그대로 두고 표시만 바꾼다

// ── 팀 설정 화면 쪽 (AI-106) ──────────────────────────────

/** local → 로컬, prod → 운영 */
export function envLabel(env) {
  return env === "prod" ? "운영" : "로컬";
}

/** "방금 전", "n분 전", "n시간 전", "n일 전" */
export function timeAgo(iso, now = Date.now()) {
  if (!iso) {
    return "";
  }
  const seconds = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000));
  if (seconds < 60) {
    return "방금 전";
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}분 전`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}시간 전`;
  }
  return `${Math.floor(hours / 24)}일 전`;
}

/** 2026-11-16 형식 날짜 */
export function dateOnly(iso) {
  if (!iso) {
    return "";
  }
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/** 클립보드 복사. 실패해도 화면은 그대로 (사용자가 직접 선택해서 복사할 수 있다) */
export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

// ── 에러 화면 쪽 (AI-99) ──────────────────────────────────

export const ENV_LABEL = { local: "로컬", prod: "운영" };
export const STATUS_LABEL = { OPEN: "열림", RESOLVED: "해결", IGNORED: "무시" };
export const PHASE_LABEL = { STARTUP: "시작 실패", RUNTIME: "실행 중 발생" };

/** 'java.lang.NullPointerException' → 'NullPointerException' */
export function shortType(exceptionType) {
  if (!exceptionType) {
    return "알 수 없는 예외";
  }
  const dot = exceptionType.lastIndexOf(".");
  return dot === -1 ? exceptionType : exceptionType.slice(dot + 1);
}

/**
 * 'dev.shop.order.OrderService#place' → { packageName, className, methodName }
 * 모양이 다르면 null (그때는 받은 글자를 그대로 보여 준다)
 */
export function splitLocation(location) {
  if (!location) {
    return null;
  }
  const hash = location.indexOf("#");
  if (hash === -1) {
    return null;
  }
  const fullClass = location.slice(0, hash);
  const dot = fullClass.lastIndexOf(".");
  return {
    packageName: dot === -1 ? "" : fullClass.slice(0, dot),
    className: dot === -1 ? fullClass : fullClass.slice(dot + 1),
    methodName: location.slice(hash + 1),
  };
}

/** 'OrderService 클래스의 place 메서드' */
export function locationText(location) {
  const parts = splitLocation(location);
  if (!parts) {
    return location ?? "위치를 알 수 없음";
  }
  return `${parts.className} 클래스의 ${parts.methodName} 메서드`;
}

/** 방금 / 3분 전 / 2시간 전 / 11월 13일 18:20 */
export function formatWhen(isoText, now = new Date()) {
  const time = new Date(isoText);
  const minutes = Math.floor((now.getTime() - time.getTime()) / 60000);
  if (minutes < 1) {
    return "방금";
  }
  if (minutes < 60) {
    return `${minutes}분 전`;
  }
  if (minutes < 60 * 24) {
    return `${Math.floor(minutes / 60)}시간 전`;
  }
  return formatDateTime(isoText);
}

/** 11월 13일 18:20 */
export function formatDateTime(isoText) {
  const time = new Date(isoText);
  const hour = String(time.getHours()).padStart(2, "0");
  const minute = String(time.getMinutes()).padStart(2, "0");
  return `${time.getMonth() + 1}월 ${time.getDate()}일 ${hour}:${minute}`;
}

/**
 * 스택에서 "우리 팀 코드"를 강조할 때 쓰는 앞머리. 위치의 패키지 앞 두 마디.
 * 'dev.shop.order.OrderService#place' → 'dev.shop.' (같은 앱의 다른 패키지도 같이 강조되게)
 * 표시용 짐작일 뿐이다. 모르면 null (강조 없음)
 */
export function teamPrefix(location) {
  const parts = splitLocation(location);
  if (!parts || !parts.packageName) {
    return null;
  }
  const segments = parts.packageName.split(".");
  return `${segments.slice(0, 2).join(".")}.`;
}

const EXPLANATION_TITLES = ["원인 후보", "확인 방법", "해결 방향"];

/**
 * AI 설명 글을 세 칸으로 나눈다. 제목 세 개가 순서대로 다 있을 때만 나누고,
 * 아니면 null (그때는 글을 통째로 보여 준다). 내용을 해석하지 않는다 — 자르기만 한다 (규칙 13)
 * @returns {{title: string, body: string}[] | null}
 */
export function splitExplanation(text) {
  if (!text) {
    return null;
  }
  const starts = [];
  let from = 0;
  for (const title of EXPLANATION_TITLES) {
    const at = text.indexOf(title, from);
    if (at === -1) {
      return null;
    }
    starts.push(at);
    from = at + title.length;
  }
  return EXPLANATION_TITLES.map((title, index) => {
    const bodyStart = starts[index] + title.length;
    const bodyEnd = index + 1 < starts.length ? starts[index + 1] : text.length;
    const body = text
      .slice(bodyStart, bodyEnd)
      .replace(/^[\s:：*#-]+/, "") // 제목 뒤의 ':' 와 꾸밈 기호
      .replace(/[\s*#]+$/, ""); // 다음 제목 앞의 꾸밈 기호
    return { title, body };
  });
}