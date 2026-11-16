import { createBrowserRouter, Navigate } from "react-router";
import { ComingSoon } from "./components/ComingSoon";
import { PlainLayout } from "./components/PlainLayout";
import { RequireLogin } from "./components/RequireLogin";
import { TeamLayout } from "./components/TeamLayout";
import { Forbidden } from "./pages/Forbidden";
import { Invite } from "./pages/Invite";
import { Login } from "./pages/Login";
import { TeamSettings } from "./pages/TeamSettings";
import { Teams } from "./pages/Teams";

export const router = createBrowserRouter([
  { path: "/login", element: <Login /> },
  { path: "/forbidden", element: <Forbidden /> },
  {
    element: <RequireLogin />,
    children: [
      { path: "/", element: <Navigate to="/teams" replace /> },
      { path: "/teams", element: <Teams /> },
      {
        path: "/teams/:teamId",
        element: <TeamLayout />,
        children: [
          { index: true, element: <Navigate to="errors" replace /> },
          { path: "errors", element: <ComingSoon title="에러" /> },
          {
            path: "errors/:groupId",
            element: <ComingSoon title="에러 상세" />,
          },
          { path: "settings", element: <TeamSettings /> },
        ],
      },
      {
        element: <PlainLayout />,
        children: [
          { path: "/admin", element: <ComingSoon title="관리자" /> },
          { path: "/invite/:token", element: <Invite /> },
        ],
      },
    ],
  },
  { path: "*", element: <Navigate to="/" replace /> },
]);
