package dev.aiops.starter.transport;

import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;
import dev.aiops.protocol.HelloRequest;
import dev.aiops.protocol.ProtocolConstants;
import dev.aiops.starter.config.AiopsSettings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 서버로 POST 한 번 (부품 ⑧). 언제 보낼지·재시도는 EventSender 몫이고, 여기서는 하지 않는다.
 * 서버가 죽어 있어도 최대 3초 안에 false. 예외를 던지지 않는다 (규칙 4).
 */
public final class HttpTransport implements Transport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final HttpClient client; // JVM당 1개, 요청마다 새로 만들지 않는다
    private final String endpoint;
    private final String apiKey;
    private final AtomicBoolean failureLogged = new AtomicBoolean();

    public HttpTransport(AiopsSettings settings) {
        this(settings.endpoint(), settings.apiKey());
    }

    public HttpTransport(String endpoint, String apiKey) {
        this.endpoint = stripTrailingSlash(endpoint);
        this.apiKey = apiKey;
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1) // h2c 업그레이드 시도 없이 바로 1.1
                .build();
    }

    @Override
    public boolean send(EventBatch batch) {
        return post(ProtocolConstants.PATH_EVENTS, batch);
    }

    @Override
    public boolean hello(HelloRequest hello) {
        return post(ProtocolConstants.PATH_HELLO, hello);
    }

    @Override
    public boolean heartbeat(Heartbeat heartbeat) {
        return post(ProtocolConstants.PATH_HEARTBEAT, heartbeat);
    }

    private boolean post(String path, Object body) {
        try {
            String json = JsonWriter.write(body);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header(ProtocolConstants.HEADER_API_KEY, apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            // 응답 본문은 읽지 않는다. 상태 코드만 본다
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return true;
            }
            logFirstFailure("HTTP " + status, path);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 종료 신호를 지우지 않는다
            return false;
        } catch (Throwable t) {
            logFirstFailure(t.getClass().getSimpleName(), path);
            return false;
        }
    }

    /** 서버가 꺼져 있으면 5초마다 실패한다. 매번 찍으면 팀 로그를 덮으니 첫 실패만 (D-087). 키는 남기지 않는다 */
    private void logFirstFailure(String reason, String path) {
        if (failureLogged.compareAndSet(false, true)) {
            System.err.println("[aiops] 전송 실패: " + reason + " " + path + " (이후 실패는 기록하지 않음)");
        }
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}