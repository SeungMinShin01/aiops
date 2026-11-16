import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { listMembers, removeMember } from "../api/team";
import { queryKeys } from "../api/queryKeys";
import { queryClient } from "../app/queryClient";
import { dateOnly } from "../app/format";
import { ConfirmDialog } from "./ConfirmDialog";

// 팀원 목록 (누구나). 내보내기 버튼은 조장에게만, 자기 자신에게는 없다 (막는 건 서버)
// props: teamId, myUserId, canManage(조장이면 true)
export function MemberTable({ teamId, myUserId, canManage }) {
  const members = useQuery({ queryKey: queryKeys.members(teamId), queryFn: () => listMembers(teamId) });
  const [removing, setRemoving] = useState(null);

  const remove = useMutation({
    mutationFn: (userId) => removeMember(teamId, userId),
    onSuccess: () => {
      setRemoving(null);
      queryClient.invalidateQueries({ queryKey: queryKeys.members(teamId) });
    },
  });

  return (
    <section className="card" aria-labelledby="members-title">
      <h2 id="members-title" className="section-title">팀원</h2>
      {members.isPending && <p className="muted">불러오는 중…</p>}
      {members.isError && <p className="error-text">팀원 목록을 불러오지 못했어요.</p>}
      {members.data && (
        <ul className="row-list">
          {members.data.map((member) => (
            <li key={member.userId} className="row">
              <div className="row-main">
                <strong>{member.name || member.login}</strong>
                <span className="muted">@{member.login}</span>
                <span className="tag">{member.role === "OWNER" ? "조장" : "팀원"}</span>
              </div>
              <div className="row-sub muted">{dateOnly(member.joinedAt)} 합류</div>
              {canManage && member.userId !== myUserId && (
                <button type="button" className="button small" onClick={() => setRemoving(member)}>
                  내보내기
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {remove.isError && <p className="error-text" role="alert">{remove.error.message}</p>}

      {removing && (
        <ConfirmDialog
          title={`${removing.name || removing.login} 님을 팀에서 내보낼까요?`}
          text="이 팀의 에러와 설정을 더 볼 수 없게 되고, 디스코드 팀 역할도 회수돼요."
          confirmLabel="내보내기"
          danger
          busy={remove.isPending}
          onConfirm={() => remove.mutate(removing.userId)}
          onCancel={() => setRemoving(null)}
        />
      )}
    </section>
  );
}
