// 화면 표기 도우미. 서버 값(local·prod, ISO 시각)을 사람이 읽는 말로 바꾼다

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
