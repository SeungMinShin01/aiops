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
  const seconds = Math.max(
    0,
    Math.floor((now - new Date(iso).getTime()) / 1000),
  );
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
  const hour = String(time.getHours()).padStart(2, "0");
  const minute = String(time.getMinutes()).padStart(2, "0");
  return `${time.getMonth() + 1}월 ${time.getDate()}일 ${hour}:${minute}`;
}
