package dev.aiops.starter.safety;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 가짜 수집 서버 (JDK 내장 HttpServer). 받은 본문을 기록하고, 응답을 늦출 수 있다 */
final class FakeIngestServer {

    /** 아무도 듣지 않는 주소 (서버 꺼짐 흉내) */
    static final String CLOSED_ENDPOINT = "http://127.0.0.1:1";

    private final HttpServer server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final List<String> eventBodies = new CopyOnWriteArrayList<>();
    private volatile long delayMillis = 0;

    private FakeIngestServer(HttpServer server) {
        this.server = server;
    }

    static FakeIngestServer start() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        FakeIngestServer fake = new FakeIngestServer(http);
        http.setExecutor(fake.pool);
        http.createContext("/", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (exchange.getRequestURI().getPath().endsWith("/events")) {
                    fake.eventBodies.add(body); // 늦추기 전에 기록한다
                }
                long delay = fake.delayMillis;
                if (delay > 0) {
                    Thread.sleep(delay); // 느린 서버 흉내
                }
                exchange.sendResponseHeaders(202, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        http.start();
        return fake;
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void delayResponses(long millis) {
        this.delayMillis = millis;
    }

    /** /v1/events로 받은 본문을 전부 이어 붙인 것 */
    String allEvents() {
        return String.join("\n", eventBodies);
    }

    /** 받은 이벤트 개수 (본문 안의 "exceptionType" 개수) */
    int eventCount() {
        return Safety.countOf(allEvents(), "\"exceptionType\"");
    }

    List<String> eventBodies() {
        return eventBodies;
    }

    void stop() {
        server.stop(0);
        pool.shutdownNow();
    }
}