package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InMemoryIndexStore;
import com.github.xingjianluo.codecontextindexerjava.model.IndexStatus;
import com.github.xingjianluo.codecontextindexerjava.model.RefreshResult;
import com.github.xingjianluo.codecontextindexerjava.scanner.DirectoryScanner;
import com.github.xingjianluo.codecontextindexerjava.scanner.ScanOutcome;
import com.github.xingjianluo.codecontextindexerjava.service.IndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发刷新必须单飞且幂等：同根目录的并发刷新共享一次扫描，
 * 不产生重复条目、计数漂移；世代单调。
 */
class ConcurrentRefreshTest {

    @Test
    void concurrentRefreshesShareOneScanAndStayConsistent(@TempDir Path root) throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.writeString(root.resolve("f" + i + ".java"),
                    "class F" + i + " {}\n", StandardCharsets.UTF_8);
        }

        IndexerProperties props = new IndexerProperties();
        props.setRoot(root.toString());
        InMemoryIndexStore store = new InMemoryIndexStore();

        int threads = 16;
        AtomicInteger scansStarted = new AtomicInteger();
        // 扫描阻塞直到主线程放行；CyclicBarrier 保证所有线程同时调用 refresh，
        // 首个建槽并卡在扫描里，其余必然看到在飞刷新而非发起第二次扫描。
        CountDownLatch scanEntered = new CountDownLatch(1);
        CountDownLatch releaseScan = new CountDownLatch(1);
        CyclicBarrier startGate = new CyclicBarrier(threads);

        DirectoryScanner slowScanner = new DirectoryScanner(props) {
            @Override
            public ScanOutcome scan(Path r) throws java.io.IOException {
                scansStarted.incrementAndGet();
                scanEntered.countDown();
                try {
                    assertTrue(releaseScan.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.scan(r);
            }
        };
        IndexService service = new IndexService(props, slowScanner, store);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<RefreshResult>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                startGate.await(10, TimeUnit.SECONDS);
                return service.refresh(null);
            }));
        }

        // 首个线程进入扫描即说明在飞槽已建立；此时其余线程正同时通过单飞判定。
        assertTrue(scanEntered.await(10, TimeUnit.SECONDS));
        // 再给其余线程一点时间完成"加入在飞"的判定，然后放行。
        Thread.sleep(100);
        releaseScan.countDown();

        Map<Long, Long> generationCounts = new ConcurrentHashMap<>();
        AtomicInteger reusedCount = new AtomicInteger();
        for (Future<RefreshResult> f : futures) {
            RefreshResult r = f.get(15, TimeUnit.SECONDS);
            generationCounts.merge(r.generation(), 1L, Long::sum);
            if (r.reusedInFlight()) {
                reusedCount.incrementAndGet();
            }
        }
        pool.shutdownNow();

        TestJudge.info("reused-in-flight", threads + " 个并发刷新",
                "恰好 1 个发起扫描（reusedInFlight=false），其余共享（true）",
                "reusedCount=" + reusedCount.get());
        assertEquals(threads - 1, reusedCount.get(),
                "除发起者外的并发刷新必须标记 reusedInFlight=true");

        TestJudge.judge("single-flight-scans", threads + " 个并发刷新",
                "actualScanCount=1", "actualScanCount=" + scansStarted.get());
        TestJudge.info("shared-generation", threads + " 个并发刷新",
                "所有并发刷新共享同一次扫描结果，断言要求恰好一个世代且计数为 16",
                "generationDistribution=" + generationCounts);
        assertEquals(1, scansStarted.get(), "并发刷新必须单飞，只允许一次实际扫描");
        assertEquals(1, generationCounts.size(), "所有并发刷新必须共享同一世代结果");
        assertEquals(threads, generationCounts.get(1L), "所有并发刷新都拿到 generation=1");

        IndexStatus status = service.status();
        TestJudge.judge("no-count-drift", root.toString(),
                "indexed=20, rejected=0", "indexed=" + status.indexedFileCount()
                        + ", rejected=" + status.rejectedCount());
        assertEquals(20, status.indexedFileCount(), "并发刷新不得造成重复条目或计数漂移");
        assertEquals(0, status.rejectedCount());
    }
}
