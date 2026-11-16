import { Link } from "react-router";
import { MessageCard } from "../components/MessageCard";

export function Forbidden() {
  return (
    <MessageCard
      title="권한이 없어요"
      text="이 팀의 멤버가 아니거나, 조장만 할 수 있는 일입니다."
    >
      <Link className="button" to="/teams">
        내 팀으로
      </Link>
    </MessageCard>
  );
}
