package main.java.dev.aiops.protocol;

/**
 * 보내는 앱이 누구인지.
 * commit: git 커밋 해시(모르면 null), dirty: 커밋 안 한 변경이 있는지(모르면 null)
 * hostHash: 호스트명의 SHA-256 앞 12자. 호스트명 원문은 보내지 않는다
 */
public record AppInfo(
        String name,
        String commit,
        Boolean dirty,
        String hostHash) {
}