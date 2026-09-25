package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InMemoryIndexStore;
import com.github.xingjianluo.codecontextindexerjava.model.IndexStatus;
import com.github.xingjianluo.codecontextindexerjava.model.RejectReason;
import com.github.xingjianluo.codecontextindexerjava.model.RejectedFile;
import com.github.xingjianluo.codecontextindexerjava.scanner.DirectoryScanner;
import com.github.xingjianluo.codecontextindexerjava.service.IndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不可读或不受支持的文件不得静默跳过：拒绝原因必须可区分、可解释。
 */
class RejectionReasonTest {

    private IndexService newService(Path root, IndexerProperties props) {
        props.setRoot(root.toString());
        InMemoryIndexStore store = new InMemoryIndexStore();
        DirectoryScanner scanner = new DirectoryScanner(props);
        return new IndexService(props, scanner, store);
    }

    @Test
    void unsupportedExtensionOversizeAndSymlinkAreDistinguished(@TempDir Path root) throws Exception {
        IndexerProperties props = new IndexerProperties();
        props.setMaxFileBytes(32);
        IndexService service = newService(root, props);

        Files.writeString(root.resolve("Good.java"), "class Good {}\n");
        Files.writeString(root.resolve("Notes.bin"), new String(new byte[]{0, 1, 2, 3}));
        Files.writeString(root.resolve("Huge.java"), "x".repeat(64));
        Path link = root.resolve("Link.java");
        Files.createSymbolicLink(link, root.resolve("Good.java"));

        service.refresh(null);
        IndexStatus status = service.status();

        Map<String, RejectReason> byPath = status.rejected().stream()
                .collect(Collectors.toMap(RejectedFile::relativePath, RejectedFile::reason));

        TestJudge.judge("unsupported", "Notes.bin",
                RejectReason.UNSUPPORTED_EXTENSION.name(), String.valueOf(byPath.get("Notes.bin")));
        TestJudge.judge("too-large", "Huge.java",
                RejectReason.TOO_LARGE.name(), String.valueOf(byPath.get("Huge.java")));
        TestJudge.judge("symlink", "Link.java",
                RejectReason.NOT_REGULAR_FILE.name(), String.valueOf(byPath.get("Link.java")));

        assertEquals(RejectReason.UNSUPPORTED_EXTENSION, byPath.get("Notes.bin"));
        assertEquals(RejectReason.TOO_LARGE, byPath.get("Huge.java"));
        assertEquals(RejectReason.NOT_REGULAR_FILE, byPath.get("Link.java"));
        assertEquals(1, status.indexedFileCount(), "仅 Good.java 被接受");
        assertEquals(3, status.rejectedCount(), "三个问题文件全部显式拒绝，无静默跳过");

        RejectedFile huge = status.rejected().stream()
                .filter(r -> r.relativePath().equals("Huge.java")).findFirst().orElseThrow();
        TestJudge.info("reject-detail", "Huge.java", "TOO_LARGE 的 detail 须含尺寸与上限", huge.detail());
        assertNotNull(huge.detail());
        assertTrue(huge.detail().contains("32"), "拒绝细节需可解释（含阈值）");
    }

    /**
     * root 用户下 chmod 000 仍可读，无法验证 UNREADABLE；运行时检测到 root 则跳过断言并说明。
     */
    @Test
    void unreadableFileGivesUnreadableReason(@TempDir Path root) throws Exception {
        if ("root".equals(System.getProperty("user.name"))) {
            TestJudge.info("unreadable-skipped", "secret.txt",
                    "当前以 root 运行，chmod 000 不阻止读取，跳过 UNREADABLE 断言", "user=root");
            return;
        }
        IndexService service = newService(root, new IndexerProperties());
        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "top secret\n");
        assertTrue(secret.toFile().setReadable(false, true), "撤销属主读权限");

        service.refresh(null);
        IndexStatus status = service.status();

        RejectReason reason = status.rejected().stream()
                .filter(r -> r.relativePath().equals("secret.txt"))
                .map(RejectedFile::reason).findFirst().orElse(null);
        TestJudge.judge("unreadable", "secret.txt (chmod 000)",
                RejectReason.UNREADABLE.name(), String.valueOf(reason));
        assertEquals(RejectReason.UNREADABLE, reason, "不可读文件须以 UNREADABLE 显式拒绝");

        // 恢复权限，便于临时目录清理。
        secret.toFile().setReadable(true, true);
    }
}
