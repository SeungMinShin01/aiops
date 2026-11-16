import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const server = "http://localhost:8080";

// 콘솔과 API는 같은 도메인이어야 한다 (세션 쿠키, D-020). 개발 중에는 프록시로 맞춘다.
// changeOrigin: false — 주소가 바뀌지 않아야 쿠키가 그대로 오간다
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: server, changeOrigin: false },
      "/oauth2": { target: server, changeOrigin: false },
      "/login/oauth2": { target: server, changeOrigin: false }, // GitHub 로그인 콜백만. /login 화면은 콘솔이 그린다
    },
  },
});
