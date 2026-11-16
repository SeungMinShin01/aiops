// 서버 값을 화면 글자로 바꾸는 함수들. 서버 값(local, OPEN …)은 그대로 두고 표시만 바꾼다

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
