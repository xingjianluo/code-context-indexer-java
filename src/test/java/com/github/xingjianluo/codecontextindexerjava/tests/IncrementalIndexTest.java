package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InMemoryIndexStore;
import com.github.xingjianluo.codecontextindexerjava.model.IndexSnapshot;
import com.github.xingjianluo.codecontextindexerjava.model.IndexStatus;
import com.github.xingjianluo.codecontextindexerjava.model.RefreshResult;
import com.github.xingjianluo.codecontextindexerjava.scanner.DirectoryScanner;
import com.github.xingjianluo.codecontextindexerjava.service.IndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增量变更：新增、修改、删除、重命名必须在索引中正确反映，不得残留过期内容。
 */
class IncrementalIndexTest {

    private record Harness(IndexService service, InMemoryIndexStore store) {
        IndexSnapshot snapshot() {
            return store.current();
        }
    }

    private Harness newHarness(Path root) {
        IndexerProperties props = new IndexerProperties();
        props.setRoot(root.toString());
        InMemoryIndexStore store = new InMemoryIndexStore();
        DirectoryScanner scanner = new DirectoryScanner(props);
        IndexService service = new IndexService(props, scanner, store);
        return new Harness(service, store);
    }

    @Test
    void addUpdateDeleteAndRenameAreReflectedExactly(@TempDir Path root) throws Exception {
        Harness h = newHarness(root);
        IndexService service = h.service();

        // --- 初始：两个文件 ---
        Files.writeString(root.resolve("Alpha.java"), "class Alpha {}\n");
        Path sub = Files.createDirectories(root.resolve("pkg"));
        Path beta = sub.resolve("Beta.java");
        Files.writeString(beta, "class Beta {}\n");

        RefreshResult first = service.refresh(null);
        assertEquals(1L, first.generation(), "首次刷新生效世代为 1");
        TestJudge.judge("initial-count", root.toString(), "2", String.valueOf(first.indexedFileCount()));
        assertEquals(2, first.indexedFileCount());

        // --- 修改 Beta ---
        Files.writeString(beta, "class Beta { int x; }\n");
        RefreshResult second = service.refresh(null);
        assertEquals(2L, second.generation());
        TestJudge.judge("updated-diff", "pkg/Beta.java",
                "updated=[pkg/Beta.java]", "updated=" + second.updated());
        assertTrue(second.updated().contains("pkg/Beta.java"));
        assertTrue(second.added().isEmpty());
        assertTrue(second.deleted().isEmpty());
        assertTrue(String.join("\n", h.snapshot().files().get("pkg/Beta.java").lines()).contains("int x"),
                "修改后的内容必须可见");

        // --- 重命名 Beta -> Gamma（同内容），旧路径必须消失 ---
        Files.move(beta, sub.resolve("Gamma.java"));
        RefreshResult third = service.refresh(null);
        TestJudge.judge("rename", "pkg/Beta.java -> pkg/Gamma.java",
                "added=[pkg/Gamma.java], deleted=[pkg/Beta.java]",
                "added=" + third.added() + ", deleted=" + third.deleted());
        assertTrue(third.added().contains("pkg/Gamma.java"));
        assertTrue(third.deleted().contains("pkg/Beta.java"));
        assertFalse(h.snapshot().files().containsKey("pkg/Beta.java"), "重命名后旧路径不得残留");
        assertTrue(h.snapshot().files().containsKey("pkg/Gamma.java"));
        assertEquals(2, h.snapshot().files().size(), "重命名不应产生重复条目或计数漂移");

        // --- 删除 Alpha ---
        Files.delete(root.resolve("Alpha.java"));
        RefreshResult fourth = service.refresh(null);
        TestJudge.judge("delete", "Alpha.java", "deleted=[Alpha.java]",
                "deleted=" + fourth.deleted());
        assertTrue(fourth.deleted().contains("Alpha.java"));
        assertFalse(h.snapshot().files().containsKey("Alpha.java"), "删除后内容不得残留");
        assertEquals(1, h.snapshot().files().size());

        // --- 重复刷新，幂等：无文件系统变更时差异为空，计数稳定 ---
        RefreshResult fifth = service.refresh(null);
        assertTrue(fifth.added().isEmpty() && fifth.updated().isEmpty() && fifth.deleted().isEmpty(),
                "无文件系统变更时差异必须为空");
        IndexStatus status = service.status();
        TestJudge.judge("idempotent-repeat", root.toString(),
                "indexed=1, rejected=0", "indexed=" + status.indexedFileCount()
                        + ", rejected=" + status.rejectedCount());
        assertEquals(1, status.indexedFileCount());
        assertEquals(0, status.rejectedCount());
    }
}
