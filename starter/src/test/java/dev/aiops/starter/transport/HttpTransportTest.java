package dev.aiops.starter.transport;

import com.sun.net.httpserver.HttpServer;
import dev.aiops.protocol.AppInfo;
import dev.aiops.protocol.EventBatch;
import dev.aiops.protocol.Heartbeat;
import dev.aiops.protocol.HelloRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** JDK 내장 HttpServer로 가짜 서버를 띄워 HttpTransport를 확인한다 */
class HttpTransportTest {

    private static final String KEY = "aiops_l_test-only-key"; // 테스트 전용 가짜 키

    private HttpServer server;
    private ExecutorService pool;
    private final CountDownLatch release = new CountDownLatch(1);

    private volatile int status = 202;
    private volatile boolean hang = false;
    private volatile String lastPath;
    private volatile String lastKey;
    private volatile String lastContentType;
    private volatile String lastBody;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.createContext("/", exchange -> {
            try {
                lastPath = exchange.getRequestURI().getPath();
                lastKey = exchange.getRequestHeaders().getFirst("X-AIOPS-KEY");
                lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
                lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (hang) {
                    release.await(10, TimeUnit.SECONDS); // 응답을 안 주는 서버 흉내
                }
                exchange.sendResponseHeaders(status, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        release.countDown();
        server.stop(0);
        pool.shutdownNow();
    }

    private HttpTransport transport() {
        return new HttpTransport("http://127.0.0.1:" + server.getAddress().getPort(), KEY);
    }

    private static AppInfo app() {
        return new AppInfo("sample-app", null, null, "0123456789ab");
    }

    private static EventBatch batch() {
        return new EventBatch("local", app(), List.of(), 0);
    }

    @Test
    void 응답_202면_true이고_경로와_헤더가_맞다() {
        assertTrue(transport().send(batch()));

        assertEquals("/v1/events", lastPath);
        assertEquals(KEY, lastKey);
        assertTrue(lastContentType.startsWith("application/json"), lastContentType);
        assertTrue(lastBody.contains("\"env\":\"local\""), lastBody);
    }

    @Test
    void 응답_500이면_false() {
        status = 500;

        assertFalse(transport().send(batch()));
    }

    @Test
    void hello와_heartbeat는_각자_경로로_간다() {
        HttpTransport transport = transport();

        assertTrue(transport.hello(new HelloRequest("local", app(), "17", "4.1.1", List.of(), List.of())));
        assertEquals("/v1/hello", lastPath);

        assertTrue(transport.heartbeat(new Heartbeat("local", app(), System.currentTimeMillis())));
        assertEquals("/v1/heartbeat", lastPath);
    }

    @Test
    void 응답을_안_주는_서버면_3_5초_안에_false() {
        hang = true;

        long start = System.nanoTime();
        assertFalse(transport().send(batch()));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue(ms < 3500, "걸린 시간: " + ms + "ms");
    }

    @Test
    void 닫힌_포트면_연결_타임아웃_안에_false() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort(); // 빈 포트를 하나 얻고 바로 닫는다
        }

        long start = System.nanoTime();
        assertFalse(new HttpTransport("http://127.0.0.1:" + port, KEY).send(batch()));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // 리눅스(CI)는 바로 거절. 윈도우는 거절된 연결을 몇 번 다시 시도해서 2초쯤 걸릴 수 있다
        assertTrue(ms < 2500, "걸린 시간: " + ms + "ms");
    }

    @Test
    void 잘못된_주소여도_예외_없이_false() {
        assertFalse(assertDoesNotThrow(() -> new HttpTransport("not a url", KEY).send(batch())));
    }

    @Test
    void main_소스에_jackson_import가_없다() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main"))) {
            List<Path> hits = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        try {
                            String text = Files.readString(p);
                            return text.contains("com.fasterxml.jackson") || text.contains("tools.jackson");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();

            assertTrue(hits.isEmpty(), "jackson을 import한 main 파일: " + hits);
        }
    }
}