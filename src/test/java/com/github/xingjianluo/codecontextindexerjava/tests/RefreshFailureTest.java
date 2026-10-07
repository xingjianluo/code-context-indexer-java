package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.index.Chunker;
import com.github.xingjianluo.codecontextindexerjava.index.FileScanner;
import com.github.xingjianluo.codecontextindexerjava.index.IndexService;
import com.github.xingjianluo.codecontextindexerjava.index.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InvalidRootDirException;
import com.github.xingjianluo.codecontextindexerjava.search.SearchService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 刷新目录参数非法：原因可区分、旧快照不受损、修正后可恢复。 */
class RefreshFailureTest {

    private static final Logger log = LoggerFactory.getLogger(RefreshFailureTest.class);

    @TempDir
    Path root;

    private IndexService indexService;
    private SearchService searchService;

    @BeforeEach
    void setUp() throws Exception {
        IndexerProperties props = new IndexerProperties();
        indexService = new IndexService(new FileScanner(props, new Chunker(props)));
        searchService = new SearchService(indexService, props);
        Files.writeString(root.resolve("ok.java"), "// 稳定内容 stable token\nclass Ok {}");
        indexService.refresh(root);
        log.info("基线快照已建立 version={}", indexService.currentSnapshot().version());
    }

    @Test
    void nonExistentDirIsRejectedWithDistinctReason() {
        Path missing = root.resolve("不存在的目录");
        InvalidRootDirException e = assertThrows(InvalidRootDirException.class,
                () -> indexService.refresh(missing));
        log.info("不存在目录 reason={} 消息={}", e.reason(), e.getMessage());
        assertEquals(InvalidRootDirException.Reason.NOT_FOUND, e.reason());
    }

    @Test
    void fileAsDirIsRejectedWithDistinctReason() throws Exception {
        Path file = root.resolve("plain.txt");
        Files.writeString(file, "not a dir");
        InvalidRootDirException e = assertThrows(InvalidRootDirException.class,
                () -> indexService.refresh(file));
        log.info("文件当目录 reason={} 消息={}", e.reason(), e.getMessage());
        assertEquals(InvalidRootDirException.Reason.NOT_A_DIRECTORY, e.reason());
    }

    @Test
    void unreadableDirIsRejectedWithDistinctReason() throws Exception {
        Path locked = Files.createDirectory(root.resolve("locked"));
        Assumptions.assumeTrue(locked.toFile().setReadable(false, false),
                "当前环境不支持修改读权限，跳过");
        try {
            Assumptions.assumeFalse(Files.isReadable(locked),
                    "以特权用户运行时目录仍可读，跳过");
            InvalidRootDirException e = assertThrows(InvalidRootDirException.class,
                    () -> indexService.refresh(locked));
            log.info("不可读目录 reason={} 消息={}", e.reason(), e.getMessage());
            assertEquals(InvalidRootDirException.Reason.UNREADABLE, e.reason());
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }

    @Test
    void failedRefreshKeepsPreviousSnapshotUsable() {
        long versionBefore = indexService.currentSnapshot().version();
        int hitsBefore = searchService.search("stable").totalHits();

        assertThrows(InvalidRootDirException.class,
                () -> indexService.refresh(root.resolve("missing")));
        assertThrows(InvalidRootDirException.class,
                () -> indexService.refresh(root.resolve("ok.java")));

        long versionAfter = indexService.currentSnapshot().version();
        int hitsAfter = searchService.search("stable").totalHits();
        log.info("失败前后 version {} -> {}，检索命中 {} -> {}",
                versionBefore, versionAfter, hitsBefore, hitsAfter);
        assertEquals(versionBefore, versionAfter, "失败刷新不得推进快照版本");
        assertTrue(hitsBefore > 0, "基线检索应有命中");
        assertEquals(hitsBefore, hitsAfter, "失败刷新后旧检索结果必须保持可用");
    }

    @Test
    void refreshRecoversAfterFailure() {
        assertThrows(InvalidRootDirException.class,
                () -> indexService.refresh(root.resolve("missing")));
        long versionAfterFailure = indexService.currentSnapshot().version();

        var report = indexService.refresh(root);
        log.info("恢复刷新 version={} indexedTotal={}", report.version(), report.indexedTotal());
        assertEquals(versionAfterFailure + 1, report.version(), "恢复后版本应正常 +1");
        assertFalse(searchService.search("stable").hits().isEmpty(),
                "换回正确目录后检索应恢复正常");
    }
}
