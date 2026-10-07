package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.index.Chunker;
import com.github.xingjianluo.codecontextindexerjava.index.FileScanner;
import com.github.xingjianluo.codecontextindexerjava.index.IndexService;
import com.github.xingjianluo.codecontextindexerjava.index.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InvalidRootDirException;
import com.github.xingjianluo.codecontextindexerjava.index.RefreshReport;
import com.github.xingjianluo.codecontextindexerjava.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 刷新目录参数非法时的行为：
 * - 不存在 / 不是目录 / 不可读 三种输入抛出可区分的原因；
 * - 失败不破坏已发布快照：版本不变，旧检索结果继续可用；
 * - 换回正确目录再刷新能恢复正常。
 */
class RefreshFailurePreservesIndexTest {

    private static final Logger log = LoggerFactory.getLogger(RefreshFailurePreservesIndexTest.class);

    @TempDir
    Path root;

    private IndexService indexService;
    private SearchService searchService;

    @BeforeEach
    void setUp() throws Exception {
        IndexerProperties props = new IndexerProperties();
        indexService = new IndexService(new FileScanner(props, new Chunker(props)));
        searchService = new SearchService(indexService, props);
        Files.writeString(root.resolve("hello.txt"), "hello 世界 keyword");
        indexService.refresh(root);
    }

    @Test
    void nonExistentDirIsRejectedAndOldIndexSurvives() {
        Path missing = root.resolve("不存在的目录");
        long versionBefore = indexService.currentSnapshot().version();

        InvalidRootDirException e = assertThrows(InvalidRootDirException.class,
                () -> indexService.refresh(missing));
        log.info("[不存在] 输入=rootDir='{}'; 判定依据: reason={} 应为 NOT_EXISTS",
                missing, e.reason());
        assertEquals(InvalidRootDirException.Reason.NOT_EXISTS, e.reason());

        assertOldIndexIntact(versionBefore, "不存在目录");
    }

    @Test
    void fileInsteadOfDirIsRejectedAndOldIndexSurvives() {
        Path file = root.resolve("hello.txt");
        long versionBefore = indexService.currentSnapshot().version();

        InvalidRootDirException e = assertThrows(InvalidRootDirException.class,
                () -> indexService.refresh(file));
        log.info("[非目录] 输入=rootDir='{}'(普通文件); 判定依据: reason={} 应为 NOT_A_DIRECTORY",
                file, e.reason());
        assertEquals(InvalidRootDirException.Reason.NOT_A_DIRECTORY, e.reason());

        assertOldIndexIntact(versionBefore, "非目录路径");
    }

    @Test
    void unreadableDirIsRejectedAndOldIndexSurvives() throws Exception {
        Path locked = Files.createDirectory(root.resolve("locked"));
        locked.toFile().setReadable(false, false);
        assumeTrue(!Files.isReadable(locked),
                "当前环境无法制造不可读目录（如 root 运行），跳过该场景");

        long versionBefore = indexService.currentSnapshot().version();
        try {
            InvalidRootDirException e = assertThrows(InvalidRootDirException.class,
                    () -> indexService.refresh(locked));
            log.info("[不可读] 输入=rootDir='{}'(无读权限); 判定依据: reason={} 应为 UNREADABLE",
                    locked, e.reason());
            assertEquals(InvalidRootDirException.Reason.UNREADABLE, e.reason());
            assertOldIndexIntact(versionBefore, "不可读目录");
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }

    @Test
    void threeFailureReasonsAreDistinguishable() throws Exception {
        Path missing = root.resolve("missing");
        Path file = root.resolve("hello.txt");
        Path locked = Files.createDirectory(root.resolve("locked2"));
        locked.toFile().setReadable(false, false);
        try {
            var r1 = assertThrows(InvalidRootDirException.class,
                    () -> indexService.refresh(missing)).reason();
            var r2 = assertThrows(InvalidRootDirException.class,
                    () -> indexService.refresh(file)).reason();
            log.info("[可区分] 输入=不存在/非目录; 判定依据: reason 分别为 {} / {}, 必须互不相同",
                    r1, r2);
            assertEquals(InvalidRootDirException.Reason.NOT_EXISTS, r1);
            assertEquals(InvalidRootDirException.Reason.NOT_A_DIRECTORY, r2);
            if (!Files.isReadable(locked)) {
                var r3 = assertThrows(InvalidRootDirException.class,
                        () -> indexService.refresh(locked)).reason();
                log.info("[可区分] 输入=不可读目录; 判定依据: reason={} 应为 UNREADABLE", r3);
                assertEquals(InvalidRootDirException.Reason.UNREADABLE, r3);
            }
        } finally {
            locked.toFile().setReadable(true, false);
        }
    }

    @Test
    void refreshRecoversAfterFailure() {
        long versionBefore = indexService.currentSnapshot().version();
        assertThrows(InvalidRootDirException.class,
                () -> indexService.refresh(root.resolve("missing")));

        RefreshReport report = indexService.refresh(root);
        log.info("[恢复] 判定依据: 失败后再次正确刷新 version={} 应为 {}+1, indexedTotal={} 应为1",
                report.version(), versionBefore, report.indexedTotal());
        assertEquals(versionBefore + 1, report.version());
        assertEquals(1, report.indexedTotal());
        assertTrue(searchService.search("keyword").totalHits() > 0);
    }

    /** 校验旧快照在失败刷新后仍然完整可用。 */
    private void assertOldIndexIntact(long versionBefore, String scenario) {
        long versionAfter = indexService.currentSnapshot().version();
        SearchService.SearchOutcome outcome = searchService.search("keyword");
        List<String> hitPaths = outcome.hits().stream()
                .map(h -> h.chunk().path()).toList();
        log.info("[旧索引可用][{}] 判定依据: version {} 应不变(={}), 检索 'keyword' 命中={} 应仍含 hello.txt",
                scenario, versionAfter, versionBefore, hitPaths);
        assertEquals(versionBefore, versionAfter, "失败刷新不得改变已发布快照版本");
        assertEquals(1, outcome.totalHits(), "失败刷新后旧检索结果必须继续可用");
        assertTrue(hitPaths.contains("hello.txt"));
    }
}
