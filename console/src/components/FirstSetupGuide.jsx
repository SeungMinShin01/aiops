import { useState } from "react";
import { copyText } from "../app/format";

// 조장이 초대 링크로 처음 들어왔을 때(?first=1) 보이는 안내 상자.
// 의존성 버전은 빌드 설정값 VITE_STARTER_VERSION (console/.env.example). 문구는 루트 README와 맞춘다
const VERSION = import.meta.env.VITE_STARTER_VERSION || "0.1.0";
const DEPENDENCY = `implementation 'com.github.SeungMinShin01.aiops:starter:${VERSION}'`;
const YAML = `aiops:
  api-key: \${AIOPS_KEY}
  env: local`;

// props: isAdmin — 키 발급은 관리자만 할 수 있다 (D-092). 조장에게는 "관리자에게 받은 키"로 안내
export function FirstSetupGuide({ isAdmin }) {
  const [copied, setCopied] = useState("");

  const copy = async (what, text) => {
    if (await copyText(text)) {
      setCopied(what);
    }
  };

  return (
    <section className="card guide" aria-labelledby="guide-title">
      <h2 id="guide-title" className="section-title">처음 연결하기</h2>
      <ol className="guide-steps">
        <li>
          {isAdmin
            ? "아래 키 칸에서 로컬 키를 발급해 복사해 두세요."
            : "관리자에게 받은 로컬 키를 준비하세요. 키는 발급할 때 한 번만 보여요."}
        </li>
        <li>
          build.gradle의 dependencies에 한 줄을 넣어요.
          <div className="copy-row">
            <code>{DEPENDENCY}</code>
            <button type="button" className="button small" onClick={() => copy("dep", DEPENDENCY)}>
              {copied === "dep" ? "복사됨" : "복사"}
            </button>
          </div>
        </li>
        <li>
          application.yml에 키를 넣어요. 키 값은 환경변수 AIOPS_KEY로 두고 커밋하지 마세요.
          <div className="copy-row">
            <pre>{YAML}</pre>
            <button type="button" className="button small" onClick={() => copy("yml", YAML)}>
              {copied === "yml" ? "복사됨" : "복사"}
            </button>
          </div>
        </li>
        <li>앱을 실행하면 위쪽 연결 상태가 "로컬 · 연결됨"으로 바뀌어요.</li>
      </ol>
    </section>
  );
}
