package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InMemoryIndexStore;
import com.github.xingjianluo.codecontextindexerjava.model.SearchResult;
import com.github.xingjianluo.codecontextindexerjava.scanner.DirectoryScanner;
import com.github.xingjianluo.codecontextindexerjava.scanner.ScanOutcome;
import com.github.xingjianluo.codecontextindexerjava.service.IndexService;
import com.github.xingjianluo.codecontextindexerjava.service.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索与刷新并发：任何检索都不得观察到尚未完成（半成品）的索引状态。
 *
 * <p>扫描器在真正扫描前阻塞于栅栏：刷新处于"进行中、尚未发布"窗口。
 * 此窗口内所有检索必须只看到 generation=0 的旧完整快照；
 * 栅栏放行、新快照发布后，任何检索一旦看到 generation=1，
 * 就必须同时看到完整的两个文件（绝不允许半成品计数）。</p>
 */
class ConcurrentSearchRefreshTest {

    @Test
    void searchesNeverObservePartialIndexState(@TempDir Path root) throws Exception {
        IndexerProperties props = new IndexerProperties();
        props.setRoot(root.toString());
        InMemoryIndexStore store = new InMemoryIndexStore();

        Files.writeString(root.resolve("One.java"), "class One {}\n");

        CountDownLatch insideScan = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        DirectoryScanner gatingScanner = new DirectoryScanner(props) {
            @Override
            public ScanOutcome scan(Path r) throws java.io.IOException {
                insideScan.countDown();
                try {
                    assertTrue(releaseScan.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.scan(r);
            }
        };
        IndexService indexService = new IndexService(props, gatingScanner, store);
        SearchService searchService = new SearchService(store, props);

        int searchThreads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(searchThreads + 1);

        // 窗口内观察统计：只允许 generation=0。
        AtomicBoolean newGenSeenBeforeRelease = new AtomicBoolean(false);
        AtomicInteger zeroGenObservations = new AtomicInteger();
        // 放行后：若看到 generation=1，其命中文件数必须恒为 2。
        AtomicBoolean incompletePublishedState = new AtomicBoolean(false);
        AtomicBoolean searching = new AtomicBoolean(true);

        Future<?> refresh = pool.submit(() -> {
            try {
                indexService.refresh(null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(insideScan.await(10, TimeUnit.SECONDS));

        List<Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < searchThreads; t++) {
            tasks.add(pool.submit(() -> {
                while (searching.get() && !Thread.currentThread().isInterrupted()) {
                    SearchResult r = searchService.search("class", 5000);
                    if (releaseScan.getCount() > 0) {
                        // 严格处于"发布前"窗口：只允许旧的完整快照。
                        if (r.generation() != 0L) {
                            newGenSeenBeforeRelease.set(true);
                        } else {
                            zeroGenObservations.incrementAndGet();
                        }
                    } else {
                        // 放行后：generation=1 必须是完整快照（2 个命中文件）。
                        if (r.generation() == 1L && r.totalHits() != 2) {
                            incompletePublishedState.set(true);
                        }
                    }
                }
            }));
        }

        // 在发布前窗口让并发检索持续跑。
        Thread.sleep(200);
        Files.writeString(root.resolve("Two.java"), "class Two {}\n");
        TestJudge.info("file-added-during-refresh", "Two.java",
                "刷新进行中写入；发布前不可见，发布后随完整快照可见", "written=true");

        releaseScan.countDown();
        refresh.get(10, TimeUnit.SECONDS);
        // 放行后继续检索一小段时间，验证发布的世代始终完整。
        Thread.sleep(100);
        searching.set(false);
        for (Future<?> task : tasks) {
            task.get(5, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        TestJudge.info("before-release-observations", "8 个检索线程",
                "发布前窗口内读到 generation=0 的次数", zeroGenObservations.get());
        TestJudge.judge("no-state-before-release", "发布前窗口的全部检索",
                "newGenSeenBeforeRelease=false",
                "newGenSeenBeforeRelease=" + newGenSeenBeforeRelease.get());
        TestJudge.judge("published-state-complete", "放行后看到 generation=1 的检索",
                "incompletePublishedState=false",
                "incompletePublishedState=" + incompletePublishedState.get());

        assertTrue(zeroGenObservations.get() > 0, "应确实有检索发生在刷新发布之前");
        assertTrue(!newGenSeenBeforeRelease.get(), "发布前不得有任何检索读到新世代");
        assertTrue(!incompletePublishedState.get(), "发布后的世代必须始终完整，不得出现半成品计数");

        SearchResult after = searchService.search("class", 5000);
        TestJudge.judge("final-state", "query='class'",
                "generation=1, totalHits=2",
                "generation=" + after.generation() + ", totalHits=" + after.totalHits());
        assertEquals(1L, after.generation());
        assertEquals(2, after.totalHits());
    }
}
