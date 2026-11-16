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
 * 14단계 팀 API 테스트용 세션 (AI-89). TestLogin(팀장님 파일)과 같은 방식으로 /_test/login 세션을 만들고,
 * TestLogin에 없는 "본문 있는 POST"와 DELETE를 CSRF 헤더와 함께 보낸다.
 */
public final class ApiSession {

    private final HttpClient http = HttpClient.newHttpClient();   // redirect를 따라가지 않는다
    private final String base;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    private ApiSession(int port) {
        this.base = "http://localhost:" + port;
    }

    public static ApiSession login(int port, long appUserId) {
        ApiSession session = new ApiSession(port);
        HttpResponse<String> res = session.send(HttpRequest.newBuilder(
                URI.create(session.base + "/_test/login/" + appUserId)).POST(HttpRequest.BodyPublishers.noBody()));
        if (res.statusCode() != 200) {
            throw new IllegalStateException("테스트 로그인 실패: " + res.statusCode());
        }
        return session;
    }

    public HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).GET());
    }

    public HttpResponse<String> post(String path, String jsonBody) {
        return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))));
    }

    public HttpResponse<String> delete(String path) {
        return send(withCsrf(HttpRequest.newBuilder(URI.create(base + path)).DELETE()));
    }

    private HttpRequest.Builder withCsrf(HttpRequest.Builder builder) {
        if (!cookies.containsKey("XSRF-TOKEN")) {
            get("/api/me");   // 첫 GET에서 XSRF-TOKEN 쿠키를 받는다
        }
        String token = cookies.get("XSRF-TOKEN");
        return token == null ? builder : builder.header("X-XSRF-TOKEN", token);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        if (!cookies.isEmpty()) {
            builder.header("Cookie", cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; ")));
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
