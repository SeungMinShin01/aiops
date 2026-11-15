package dev.aiops.starter.env;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 작업 폴더에서 위로 올라가며 .git을 찾아 현재 커밋 해시를 읽는다 (AI-47).
 * git 명령을 실행하지 않고 파일만 읽는다 (새 의존성·프로세스 실행 금지, 규칙 3).
 * 앱 시작 때 한 번만 부른다. 못 읽으면 commit은 null. 예외를 던지지 않는다.
 * dirty는 항상 null(모름): 파일 시각으로는 믿을 만하게 알 수 없다.
 */
public final class GitInfoReader {

    /** commit: 40자 해시 또는 null. dirty: 커밋 안 한 변경이 있는지, 모르면 null */
    public record GitInfo(String commit, Boolean dirty) {
    }

    static final GitInfo UNKNOWN = new GitInfo(null, null);
    static final int MAX_PARENTS = 10;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{40}");

    private GitInfoReader() {
    }

    public static GitInfo read() {
        try {
            return read(Path.of("").toAbsolutePath());
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    static GitInfo read(Path start) {
        try {
            Path dir = start;
            for (int i = 0; i < MAX_PARENTS && dir != null; i++) {
                Path git = dir.resolve(".git");
                if (Files.isDirectory(git)) {
                    return new GitInfo(commitOf(git), null);
                }
                dir = dir.getParent();
            }
        } catch (Throwable t) {
            // 못 읽으면 모름
        }
        return UNKNOWN;
    }

    private static String commitOf(Path git) throws IOException {
        String head = Files.readString(git.resolve("HEAD")).trim();
        if (!head.startsWith("ref:")) {
            return validOrNull(head); // 브랜치 없이 커밋을 직접 가리키는 상태
        }
        String ref = head.substring(4).trim(); // refs/heads/main
        if (ref.isEmpty() || ref.contains("..")) {
            return null;
        }
        Path refFile = git.resolve(ref);
        if (Files.isRegularFile(refFile)) {
            return validOrNull(Files.readString(refFile).trim());
        }
        // 브랜치 파일이 없으면 묶음 파일(packed-refs)에 "해시 이름" 줄로 들어 있다
        Path packed = git.resolve("packed-refs");
        if (Files.isRegularFile(packed)) {
            for (String line : Files.readAllLines(packed)) {
                if (line.endsWith(" " + ref)) {
                    return validOrNull(line.substring(0, line.indexOf(' ')));
                }
            }
        }
        return null;
    }

    private static String validOrNull(String value) {
        return value != null && HASH.matcher(value).matches() ? value : null;
    }
}