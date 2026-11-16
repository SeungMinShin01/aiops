// 디스코드 연결 (누구나). 채널 상태는 /api/me teams[].discordStatus, 내가 연결했는지는 discordConnected (D-110).
// [디스코드 연결]은 fetch가 아니라 링크 이동이다 — 서버가 디스코드로 redirect하고, 끝나면 ?discord=connected|failed로 돌아온다.
// 웹훅 주소·채널 번호는 화면에 없다 (API가 주지 않음, 규칙 40)
// props: teamId, teamName, discordStatus, discordConnected, outcome(?discord= 값)
export function DiscordSection({ teamId, teamName, discordStatus, discordConnected, outcome }) {
  const ready = discordStatus === "READY";
  return (
    <section className="card" aria-labelledby="discord-title">
      <h2 id="discord-title" className="section-title">디스코드</h2>

      {outcome === "connected" && (
        <p className="success-box" role="status">
          연결됐어요. 디스코드에서 #{teamName}-local 채널을 확인하세요.
        </p>
      )}
      {outcome === "failed" && (
        <p className="warning-box" role="alert">
          연결하지 못했어요. 서버 초대 링크로 직접 입장한 뒤 관리자에게 역할을 요청하세요.
        </p>
      )}

      {!ready && <p className="muted">알림 채널 준비 중이에요. 관리자가 확인하고 있어요.</p>}
      {ready && discordConnected && (
        <p className="muted">
          디스코드 계정이 연결돼 있어요.{" "}
          <a href={`/api/discord/connect?teamId=${teamId}`}>이 팀 채널이 안 보이면 다시 연결</a>
        </p>
      )}
      {ready && !discordConnected && (
        <>
          <p className="muted">디스코드를 연결하면 이 팀의 에러 알림 채널에 들어가요.</p>
          <a className="button primary" href={`/api/discord/connect?teamId=${teamId}`}>
            디스코드 연결
          </a>
        </>
      )}
    </section>
  );
}
