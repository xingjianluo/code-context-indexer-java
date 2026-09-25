package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.index.Chunker;
import com.github.xingjianluo.codecontextindexerjava.index.FileScanner;
import com.github.xingjianluo.codecontextindexerjava.index.IndexService;
import com.github.xingjianluo.codecontextindexerjava.index.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.RefreshReport;
import com.github.xingjianluo.codecontextindexerjava.model.IndexSnapshot;
import com.github.xingjianluo.codecontextindexerjava.search.SearchHit;
import com.github.xingjianluo.codecontextindexerjava.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 并发刷新与检索：快照原子发布、刷新串行幂等、计数不漂移。 */
class ConcurrentRefreshSearchTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentRefreshSearchTest.class);

    @TempDir
    Path root;

    private IndexService indexService;
    private SearchService searchService;

    @BeforeEach
    void setUp() {
        IndexerProperties props = new IndexerProperties();
        indexService = new IndexService(new FileScanner(props, new Chunker(props)));
        searchService = new SearchService(indexService, props);
    }

    @Test
    void concurrentRefreshesAreSerializedAndIdempotent() throws Exception {
        Files.writeString(root.resolve("a.java"), "class A { void alpha() {} }");
        Files.writeString(root.resolve("b.java"), "class B { void beta() {} }");
        Files.writeString(root.resolve("c.md"), "# gamma doc");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RefreshReport>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return indexService.refresh(root);
            }));
        }
        start.countDown();
        List<RefreshReport> reports = new ArrayList<>();
        for (Future<RefreshReport> f : futures) {
            reports.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        IndexSnapshot s = indexService.currentSnapshot();
        log.info("[并发刷新] 输入=8个并发刷新; 判定依据: version={} 应为8(每次串行+1), "
                + "indexedTotal={} 应为3", s.version(), s.indexedCount());
        assertEquals(threads, s.version(), "每次刷新串行执行且版本恰好+1");
        assertEquals(3, s.indexedCount());

        // 幂等：除首个报告外，其余报告均为全 unchanged，无新增/删除/修改
        long nonTrivial = reports.stream()
                .filter(r -> r.added() + r.modified() + r.removed() > 0).count();
        log.info("[幂等性] 判定依据: 有差异的报告数={} 应为1(仅首个), 全部报告 indexedTotal 一致={}",
                nonTrivial, reports.stream().map(RefreshReport::indexedTotal).distinct().toList());
        assertEquals(1, nonTrivial);
        assertTrue(reports.stream().allMatch(r -> r.indexedTotal() == 3));

        // 无重复条目：(path, chunkIndex) 唯一
        Set<String> keys = new HashSet<>();
        s.allChunks().forEach(c -> assertTrue(
                keys.add(c.path() + "#" + c.chunkIndex()), "不得出现重复片段"));
    }

    @Test
    void searchNeverObservesPartialIndexState() throws Exception {
        Path file = root.resolve("flip.java");
        String genA = "class Flip { void alphaToken() {} }";
        String genB = "class Flip { void omegaToken() {} }";
        Files.writeString(file, genA);
        indexService.refresh(root);

        AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        // 刷新线程：在两代内容间反复切换并刷新
        Future<?> refresher = pool.submit(() -> {
            int i = 0;
            while (!stop.get()) {
                try {
                    Files.writeString(file, (i++ % 2 == 0) ? genB : genA);
                    indexService.refresh(root);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        // 检索线程：任何一次检索结果必须整体属于某一代，不得混杂
        Future<?> searcher = pool.submit(() -> {
            int checks = 0;
            while (!stop.get()) {
                SearchService.SearchOutcome outcome = searchService.search("alphaToken omegaToken");
                List<SearchHit> hits = outcome.hits();
                boolean allAlpha = hits.stream().allMatch(h -> h.chunk().content().contains("alphaToken"));
                boolean allOmega = hits.stream().allMatch(h -> h.chunk().content().contains("omegaToken"));
                if (!hits.isEmpty() && !allAlpha && !allOmega) {
                    throw new IllegalStateException(
                            "检索观察到混杂状态 version=" + outcome.snapshotVersion());
                }
                checks++;
            }
            return checks;
        });

        Thread.sleep(1500);
        stop.set(true);
        Integer checks = (Integer) searcher.get(30, TimeUnit.SECONDS);
        refresher.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        log.info("[并发检索] 判定依据: 完成一致性校验 {} 次，每次命中必须整体属于同一代内容", checks);
        assertTrue(checks > 0, "并发期间应至少完成一次检索校验");
    }
}
