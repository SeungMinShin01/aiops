package dev.aiops.starter.safety;

import ch.qos.logback.classic.LoggerContext;
import com.example.safetyapp.SafetyApp;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 안전성 테스트 공통 도구 (AI-49). Starter를 블랙박스로 본다:
 * 설정·HTTP·로그·스레드 이름으로만 확인하고 Starter 내부 클래스는 부르지 않는다.
 */
final class Safety {

    /** 가짜 키 (진짜 키 아님) */
    static final String KEY_ARG = "--aiops.api-key=aiops_l_safety-test";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private Safety() {
    }

    /** 테스트용 앱을 빈 포트에 띄운다. 전송 주기는 1초로 줄인다 (테스트가 오래 기다리지 않게) */
    static ConfigurableApplicationContext startApp(String... extraArgs) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--aiops.flush-interval=1s"));
        args.addAll(List.of(extraArgs));
        SpringApplication app = new SpringApplication(SafetyApp.class);
        app.setRegisterShutdownHook(false);
        return app.run(args.toArray(new String[0]));
    }

    static int portOf(ConfigurableApplicationContext context) {
        return Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    }

    /** GET 한 번. 응답 상태 코드 */
    static int get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    /** 조건이 참이 될 때까지 짧게 반복 확인. 시간 안에 참이 되면 true */
    static boolean pollUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    /** 루트 로거에 이름이 AIOPS인 어펜더가 붙어 있는지 */
    static boolean hasAiopsAppender() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            return false;
        }
        return context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).getAppender("AIOPS") != null;
    }

    static boolean senderThreadAlive() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("aiops-sender") && t.isAlive()) {
                return true;
            }
        }
        return false;
    }

    static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    /** prefix로 시작하는 줄 수 */
    static long linesStartingWith(String text, String prefix) {
        return text.lines().filter(line -> line.startsWith(prefix)).count();
    }
}