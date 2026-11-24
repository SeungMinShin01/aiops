package dev.aiops.server.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 테스트용 로그인 (AI-76). app_user id로 세션 쿠키를 받아 그 쿠키로 요청한다.
 * 진짜 브라우저처럼 Set-Cookie를 모아 두고, 변경 요청에는 XSRF-TOKEN 쿠키 값을 X-XSRF-TOKEN 헤더로 되돌린다.
 * GitHub OAuth 자체는 수동 확인 항목이다.
 */
public final class TestLogin {

    private final HttpClient http = HttpClient.newHttpClient(); // redirect를 따라가지 않는다 (302를 그대로 본다)
    private final String base;

    public TestLogin(int port) {
        this.base = "http://localhost:" + port;
    }

    public Session as(long appUserId) {
        Session session = new Session();
        HttpResponse<String> res = session.send(HttpRequest.newBuilder(URI.create(base + "/_test/login/" + appUserId))
                .POST(HttpRequest.BodyPublishers.noBody()));
        if (res.statusCode() != 200) {
            throw new IllegalStateException("테스트 로그인 실패: " + res.statusCode());
        }
        return session;
    }

    public Session anonymous() {
        return new Session();
    }

    public final class Session {

        private final Map<String, String> cookies = new LinkedHashMap<>();

        public HttpResponse<String> get(String path) {
            return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
        }

        /** CSRF 토큰이 없으면 먼저 GET /api/me로 받아 온 뒤 헤더에 싣는다 */
        public HttpResponse<String> post(String path) {
            if (!cookies.containsKey("XSRF-TOKEN")) {
                get("/api/me");
            }
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"));
            String token = cookies.get("XSRF-TOKEN");
            if (token != null) {
                req.header("X-XSRF-TOKEN", token);
            }
            return send(req);
        }

        private HttpResponse<String> send(HttpRequest.Builder builder) {
            if (!cookies.isEmpty()) {
                builder.header("Cookie", cookies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .collect(Collectors.joining("; ")));
            }
            try {
                HttpResponse<String> res = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                for (String header : res.headers().allValues("set-cookie")) {
                    String pair = header.split(";", 2)[0];
                    int eq = pair.indexOf('=');
                    String name = pair.substring(0, eq).trim();
                    String value = pair.substring(eq + 1).trim();
                    if (value.isEmpty() || header.toLowerCase().contains("max-age=0")) {
                        cookies.remove(name);
                    } else {
                        cookies.put(name, value);
                    }
                }
                return res;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}