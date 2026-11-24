import { useEffect, useRef } from "react";
import { useMutation } from "@tanstack/react-query";
import { useNavigate, useParams } from "react-router";
import { acceptInvite } from "../api/team";
import { queryKeys } from "../api/queryKeys";
import { queryClient } from "../app/queryClient";
import { MessageCard } from "../components/MessageCard";

// /invite/:token — 초대 수락 (AI-105 API). RequireLogin 안이라 로그인 안 돼 있으면
// client.js가 /login?next=/invite/<token>으로 보내고, 로그인 후 서버가 이리로 돌려보낸다 (AI-106 ?next=)
export function Invite() {
  const { token } = useParams();
  const navigate = useNavigate();
  const started = useRef(false);

  const accept = useMutation({
    mutationFn: () => acceptInvite(token),
    onSuccess: async (result) => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.me }); // 새 팀이 내 팀 목록에 생긴다
      const target =
        result.next === "key-guide"
          ? `/teams/${result.teamId}/settings?first=1`
          : `/teams/${result.teamId}/errors`;
      navigate(target, { replace: true });
    },
  });

  // 들어오자마자 한 번만 수락한다 (StrictMode의 두 번 실행에도 한 번)
  useEffect(() => {
    if (!started.current) {
      started.current = true;
      accept.mutate();
    }
  }, [accept]);

  if (accept.isError) {
    const gone = accept.error.status === 404 || accept.error.status === 410;
    return (
      <MessageCard
        title={gone ? "링크를 쓸 수 없어요" : "초대를 수락하지 못했어요"}
        text={
          gone
            ? "링크가 유효하지 않거나 만료됐어요. 만든 사람에게 새 링크를 요청하세요."
            : accept.error.message
        }
      />
    );
  }
  return <p className="page-message">초대를 수락하는 중…</p>;
}
