package dev.aiops.starter.env;

import dev.aiops.protocol.Dependency;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 클래스패스의 jar 파일 이름에서 (이름, 버전)을 뽑는다 (AI-47).
 * 파일 이름만 쓴다. 폴더 경로는 보내지 않는다 (사용자 이름이 들어 있다).
 * 모양이 안 맞는 파일은 건너뛴다. 최대 300개. 예외를 던지지 않는다.
 */
final class DependencyLister {

    static final int MAX = 300;
    // spring-web-6.1.5.jar → (spring-web, 6.1.5). "-숫자"가 처음 나오는 곳부터 버전
    private static final Pattern JAR = Pattern.compile("^(.+?)-(\\d[A-Za-z0-9._+\\-]*)\\.jar$");

    private DependencyLister() {
    }

    static List<Dependency> list() {
        try {
            Set<String> names = new LinkedHashSet<>();
            // 앱 설정이 아니라 JVM이 정한 값이다 (규칙 6의 "설정 값"과 다름)
            String classPath = System.getProperty("java.class.path", "");
            for (String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
                names.add(fileNameOf(entry));
            }
            // 실행 jar(java -jar)는 안에 든 라이브러리가 java.class.path에 없어서 클래스로더에서도 찾는다
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            for (int depth = 0; loader != null && depth < 10; depth++) {
                if (loader instanceof URLClassLoader urlLoader) {
                    for (URL url : urlLoader.getURLs()) {
                        names.add(fileNameOf(url.toString()));
                    }
                }
                loader = loader.getParent();
            }
            return fromFileNames(names);
        } catch (Throwable t) {
            return List.of();
        }
    }

    static List<Dependency> fromFileNames(Collection<String> fileNames) {
        List<Dependency> result = new ArrayList<>();
        for (String name : fileNames) {
            if (result.size() >= MAX) {
                break;
            }
            Dependency dependency = parse(name);
            if (dependency != null) {
                result.add(dependency);
            }
        }
        return result;
    }

    /** 모양이 안 맞으면 null. group은 파일 이름으로 알 수 없어 null */
    static Dependency parse(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher matcher = JAR.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        return new Dependency(null, matcher.group(1), matcher.group(2));
    }

    /** 경로나 주소에서 마지막 파일 이름만. 끝의 "/"와 "!"(jar 안의 jar 표시)는 뗀다 */
    static String fileNameOf(String entry) {
        if (entry == null) {
            return "";
        }
        String s = entry.replace('\\', '/');
        while (s.endsWith("/") || s.endsWith("!")) {
            s = s.substring(0, s.length() - 1);
        }
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}