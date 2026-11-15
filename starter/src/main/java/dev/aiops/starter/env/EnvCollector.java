package dev.aiops.starter.env;

import dev.aiops.protocol.HelloRequest;
import org.springframework.core.env.Environment;

/**
 * 부품 ⑨. 앱이 준비된 뒤 환경 정보를 한 번 모은다 (AI-47).
 * 설정 "값"은 읽지 않는다. 키 이름과 출처만 (규칙 6).
 * 어떤 부분이 실패해도 그 칸만 비우고 나머지는 돌려준다. 예외를 던지지 않는다.
 */
public interface EnvCollector {

    HelloRequest collect(Environment environment);
}