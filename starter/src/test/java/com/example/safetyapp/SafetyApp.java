package com.example.safetyapp;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * 안전성 테스트 전용 최소 앱 (AI-49). 다른 팀 앱처럼 보이게 dev.aiops 밖의 패키지에 둔다
 * (Starter는 dev.aiops 로거를 무시한다). sample-app 모듈에 기대지 않으려고 테스트 안에 둔다.
 */
@SpringBootApplication
@RestController
public class SafetyApp {

    @GetMapping("/ok")
    public Map<String, String> ok() {
        return Map.of("status", "ok");
    }

    @GetMapping("/boom/npe")
    public String npe() {
        String s = null;
        return String.valueOf(s.length());
    }

    /** 값은 전부 가짜다 (마스킹 확인용) */
    @GetMapping("/boom/sensitive")
    public String sensitive() {
        throw new IllegalStateException(
                "login failed password=1234 user=test@example.com token=abc.def.ghi");
    }

    /** Starter가 무시해야 하는 로거로 ERROR + 예외를 찍는다 (전송 로그가 다시 수집되는 일을 흉내) */
    @GetMapping("/log/ignored")
    public Map<String, String> ignoredLoggers() {
        LoggerFactory.getLogger("jdk.internal.httpclient.debug").error("전송 실패 흉내", new IOException("a"));
        LoggerFactory.getLogger("java.net.http.HttpClient").error("전송 실패 흉내", new IOException("b"));
        LoggerFactory.getLogger("dev.aiops.starter.transport.HttpTransport").error("전송 실패 흉내", new IOException("c"));
        return Map.of("status", "ok");
    }

    /** --safety.fail=true 일 때만: Bean 생성에서 실패 (시작 실패 흉내) */
    @Bean
    @ConditionalOnProperty(name = "safety.fail", havingValue = "true")
    Object failingBean() {
        throw new IllegalStateException("db connect failed: password=hunter2secret");
    }
}