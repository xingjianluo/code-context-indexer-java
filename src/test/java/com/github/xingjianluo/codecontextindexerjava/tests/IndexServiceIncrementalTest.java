package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.index.Chunker;
import com.github.xingjianluo.codecontextindexerjava.index.FileScanner;
import com.github.xingjianluo.codecontextindexerjava.index.IndexService;
import com.github.xingjianluo.codecontextindexerjava.index.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.RefreshReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 增量变更场景：新增、修改、删除、重命名都必须正确反映在索引中。 */
class IndexServiceIncrementalTest {

    private static final Logger log = LoggerFactory.getLogger(IndexServiceIncrementalTest.class);

    @TempDir
    Path root;

    private IndexService indexService;

    @BeforeEach
    void setUp() {
        IndexerProperties props = new IndexerProperties();
        indexService = new IndexService(new FileScanner(props, new Chunker(props)));
    }

    @Test
    void addModifyDeleteRenameAreReflected() throws Exception {
        Files.writeString(root.resolve("a.java"), "class A { void alpha() {} }");
        Files.writeString(root.resolve("b.java"), "class B { void beta() {} }");

        RefreshReport r1 = indexService.refresh(root);
        log.info("[初始刷新] 输入=a.java,b.java; 判定依据: added={} 应为2, indexedTotal={} 应为2",
                r1.added(), r1.indexedTotal());
        assertEquals(2, r1.added());
        assertEquals(2, r1.indexedTotal());

        // 修改 a.java
        Files.writeString(root.resolve("a.java"), "class A { void alphaChanged() {} }");
        RefreshReport r2 = indexService.refresh(root);
        log.info("[修改] 受影响输入=a.java; 判定依据: modified={} 应为1, unchanged={} 应为1",
                r2.modified(), r2.unchanged());
        assertEquals(1, r2.modified());
        assertEquals(1, r2.unchanged());
        assertTrue(indexService.currentSnapshot().indexedFile("a.java").orElseThrow()
                .chunks().toString().contains("alphaChanged"));

        // 删除 b.java
        Files.delete(root.resolve("b.java"));
        RefreshReport r3 = indexService.refresh(root);
        log.info("[删除] 受影响输入=b.java; 判定依据: removed={} 应为1, indexedTotal={} 应为1",
                r3.removed(), r3.indexedTotal());
        assertEquals(1, r3.removed());
        assertEquals(1, r3.indexedTotal());
        assertFalse(indexService.currentSnapshot().indexedFiles().containsKey("b.java"),
                "删除后不得残留过期内容");

        // 重命名 a.java -> c.java（等价于删除 a + 新增 c）
        Files.move(root.resolve("a.java"), root.resolve("c.java"));
        RefreshReport r4 = indexService.refresh(root);
        log.info("[重命名] 受影响输入=a.java->c.java; 判定依据: removed={} 应为1, added={} 应为1",
                r4.removed(), r4.added());
        assertEquals(1, r4.removed());
        assertEquals(1, r4.added());
        assertFalse(indexService.currentSnapshot().indexedFiles().containsKey("a.java"));
        assertTrue(indexService.currentSnapshot().indexedFiles().containsKey("c.java"));

        // 幂等：无变更再刷新，计数不得漂移
        RefreshReport r5 = indexService.refresh(root);
        log.info("[重复刷新] 判定依据: added={} modified={} removed={} 应全为0, unchanged={} 应为1",
                r5.added(), r5.modified(), r5.removed(), r5.unchanged());
        assertEquals(0, r5.added());
        assertEquals(0, r5.modified());
        assertEquals(0, r5.removed());
        assertEquals(1, r5.unchanged());
        assertEquals(1, r5.indexedTotal());
    }
}
