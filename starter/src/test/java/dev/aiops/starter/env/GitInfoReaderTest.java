package dev.aiops.starter.env;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

// AI-47 : 임시 폴더에 가짜 .git을 만들어 읽는다
class GitInfoReaderTest {

    static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path root;

    private Path gitDir() throws IOException {
        return Files.createDirectories(root.resolve(".git"));
    }

    @Test
    void 브랜치를_가리키면_그_브랜치_파일의_해시를_읽는다() throws IOException {
        Path git = gitDir();
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.createDirectories(git.resolve("refs/heads"));
        Files.writeString(git.resolve("refs/heads/main"), HASH + "\n");

        GitInfoReader.GitInfo info = GitInfoReader.read(root);

        assertEquals(HASH, info.commit());
        assertNull(info.dirty(), "dirty는 모름");
    }

    @Test
    void 하위_폴더에서_시작해도_위로_올라가_찾는다() throws IOException {
        Path git = gitDir();
        Files.writeString(git.resolve("HEAD"), HASH + "\n"); // 커밋을 직접 가리키는 상태
        Path sub = Files.createDirectories(root.resolve("sample-app/src"));

        assertEquals(HASH, GitInfoReader.read(sub).commit());
    }

    @Test
    void 브랜치_파일이_없으면_packed_refs에서_찾는다() throws IOException {
        Path git = gitDir();
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/develop\n");
        Files.writeString(git.resolve("packed-refs"),
                "# pack-refs with: peeled fully-peeled sorted\n" + HASH + " refs/heads/develop\n");

        assertEquals(HASH, GitInfoReader.read(root).commit());
    }

    @Test
    void git_폴더가_없으면_null() {
        GitInfoReader.GitInfo info = GitInfoReader.read(root);

        assertNull(info.commit());
        assertNull(info.dirty());
    }

    @Test
    void 해시_모양이_아니면_null() throws IOException {
        Path git = gitDir();
        Files.writeString(git.resolve("HEAD"), "이상한 내용\n");

        assertNull(GitInfoReader.read(root).commit());
    }

    @Test
    void 실제_작업_폴더에서_불러도_예외가_없다() {
        assertDoesNotThrow(() -> GitInfoReader.read());
    }
}