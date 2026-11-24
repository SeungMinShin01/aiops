package dev.aiops.server.service;

import dev.aiops.protocol.StackFrame;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

// AI-51 : "같은 에러"를 결정하는 지문 계산. 순수 함수 (Spring·DB·시간 의존 없음)
public class Fingerprinter {

    // AI-51 : 프레임워크 클래스로 보는 접두사 10개. 고정 (늘리면 테스트 기대값이 바뀜 → 설계자에게)
    static final List<String> FRAMEWORK_PREFIXES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.",
            "org.springframework.", "org.hibernate.", "com.zaxxer.", "org.apache.", "dev.aiops.");

    // AI-51 : 예외 타입 + 팀 코드 상위 3프레임(클래스#메서드, 줄 번호 제외) → SHA-256 hex 소문자 64자
    public String fingerprint(String exceptionType, List<StackFrame> frames) {
        List<String> teamFrames = new ArrayList<>();   // 프레임워크가 아닌 프레임만
        List<String> allFrames = new ArrayList<>();    // 전부 (팀 프레임이 하나도 없을 때 대신 씀)

        if (frames != null) {
            for (StackFrame frame : frames) {
                String name = frame.className() + "#" + frame.methodName();   // 줄 번호는 안 붙임
                allFrames.add(name);
                if (!isFramework(frame.className())) {
                    teamFrames.add(name);
                }
            }
        }

        // 팀 프레임이 있으면 그것만, 하나도 없으면 전체에서 고름
        List<String> chosen = teamFrames.isEmpty() ? allFrames : teamFrames;

        // "예외타입|프레임1|프레임2|프레임3" (모자라면 있는 것만, 빈 스택이면 예외 타입만)
        StringBuilder text = new StringBuilder(exceptionType);
        for (int i = 0; i < chosen.size() && i < 3; i++) {
            text.append("|").append(chosen.get(i));
        }
        return sha256Hex(text.toString());
    }

    // 클래스 이름이 프레임워크 접두사로 시작하는지
    private boolean isFramework(String className) {
        if (className == null) {
            return false;
        }
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // AI-51 : 글자를 SHA-256 으로 해시해서 hex 소문자로 (hashCode·MD5 쓰지 않음)
    private String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 은 자바에 항상 있어서 실제로는 안 일어남
        }
    }
}