package com.github.xingjianluo.codecontextindexerjava.service;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InMemoryIndexStore;
import com.github.xingjianluo.codecontextindexerjava.model.IndexSnapshot;
import com.github.xingjianluo.codecontextindexerjava.model.IndexStatus;
import com.github.xingjianluo.codecontextindexerjava.model.IndexedFile;
import com.github.xingjianluo.codecontextindexerjava.model.RefreshResult;
import com.github.xingjianluo.codecontextindexerjava.scanner.DirectoryScanner;
import com.github.xingjianluo.codecontextindexerjava.scanner.ScanOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 索引与刷新协议实现。
 *
 * <h3>一致性边界</h3>
 * <ul>
 *   <li><b>单飞（single-flight）刷新</b>：同一时刻只有一个线程实际扫描目录；
 *       扫描期间到达的并发刷新请求共享同一次 {@link CompletableFuture} 的结果
 *       （{@code reusedInFlight=true}），因此重复/并发刷新天然幂等，
 *       不会出现重复条目、重复计数或结果随机波动。</li>
 *   <li><b>快照原子发布</b>：扫描在副本上进行，完成后整体替换 {@link InMemoryIndexStore}
 *       中的不可变快照。任何检索读到的要么是旧的完整快照，要么是新的完整快照，
 *       永远观察不到刷新中间态。</li>
 *   <li><b>世代单调递增</b>：每次真正的扫描发布 generation+1，世代号只在单飞锁内递增，
 *       检索结果携带世代号，便于判断"结论基于哪一版索引"。</li>
 *   <li><b>单根约束</b>：服务只绑定一个根目录；对其他根目录的并发刷新会得到 409，
 *       避免两个索引互相覆盖。</li>
 * </ul>
 */
@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final IndexerProperties properties;
    private final DirectoryScanner scanner;
    private final InMemoryIndexStore store;

    private final ReentrantLock flightLock = new ReentrantLock();
    private volatile CompletableFuture<RefreshResult> inFlight;
    /** 当前在飞扫描的根目录；用于首次发布前也能拒绝不同根目录的并发刷新。 */
    private volatile Path inFlightRoot;

    public IndexService(IndexerProperties properties, DirectoryScanner scanner, InMemoryIndexStore store) {
        this.properties = properties;
        this.scanner = scanner;
        this.store = store;
    }

    /**
     * 刷新索引。
     *
     * @param requestedRoot 显式指定的根目录；为 null 时使用 cci.root
     * @return 本次（或共享中的）刷新结果
     * @throws IOException 根目录无效或扫描发生 I/O 错误
     */
    public RefreshResult refresh(Path requestedRoot) throws IOException {
        Path root = resolveRoot(requestedRoot);

        CompletableFuture<RefreshResult> future;
        boolean joinExisting;
        flightLock.lock();
        try {
            CompletableFuture<RefreshResult> running = inFlight;
            if (running != null && !running.isDone()) {
                Path activeRoot = inFlightRoot;
                if (activeRoot != null && !activeRoot.equals(root)) {
                    throw new IllegalStateException(
                            "another refresh is in progress for root " + activeRoot
                                    + "; concurrent refresh for a different root is not allowed");
                }
                future = running;
                joinExisting = true;
            } else {
                future = startRefresh(root);
                joinExisting = false;
            }
        } finally {
            flightLock.unlock();
        }

        try {
            RefreshResult result = future.join();
            if (joinExisting) {
                // 与发起者共享同一次扫描结果：标记 reusedInFlight，保持世代与差异不变。
                result = new RefreshResult(
                        result.generation(), result.root(), true,
                        result.added(), result.updated(), result.deleted(),
                        result.rejected(), result.indexedFileCount(),
                        result.rejectedCount(), result.durationMillis());
                log.info("refresh request joined in-flight scan for root={}, generation={}",
                        root, result.generation());
            }
            return result;
        } catch (CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof IOException ioe) {
                throw ioe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IOException(cause);
        }
    }

    private CompletableFuture<RefreshResult> startRefresh(Path root) {
        CompletableFuture<RefreshResult> future = CompletableFuture.supplyAsync(() -> {
            long started = System.currentTimeMillis();
            try {
                log.info("refresh scan started: root={}", root);
                IndexSnapshot previous = store.current();
                ScanOutcome outcome = scanner.scan(root);
                long finished = System.currentTimeMillis();

                long generation;
                flightLock.lock();
                try {
                    generation = previous.generation() + 1;
                    IndexSnapshot snapshot = new IndexSnapshot(
                            root, generation, started, finished,
                            outcome.accepted(), outcome.rejected());
                    store.publish(snapshot);
                } finally {
                    flightLock.unlock();
                }

                Diff diff = diff(previous, outcome);
                log.info("refresh published: root={}, generation={}, added={}, updated={}, deleted={}, rejected={}",
                        root, generation, diff.added.size(), diff.updated.size(),
                        diff.deleted.size(), outcome.rejected().size());
                return new RefreshResult(
                        generation, root.toString(), false,
                        diff.added, diff.updated, diff.deleted,
                        outcome.rejected(), outcome.accepted().size(),
                        outcome.rejected().size(), finished - started);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        // 完成后在持有锁的前提下清空在飞槽；身份比较保证不会误清新一轮刷新。
        future.whenComplete((result, error) -> {
            flightLock.lock();
            try {
                if (inFlight == future) {
                    inFlight = null;
                    inFlightRoot = null;
                }
            } finally {
                flightLock.unlock();
            }
        });
        inFlight = future;
        inFlightRoot = root;
        return future;
    }

    /**
     * 当前索引状态。未初始化时 initialized=false，其余计数为 0，绝不抛异常。
     */
    public IndexStatus status() {
        IndexSnapshot snapshot = store.current();
        boolean initialized = snapshot.generation() > 0;
        return new IndexStatus(
                initialized,
                snapshot.generation(),
                snapshot.root() == null ? null : snapshot.root().toString(),
                snapshot.files().size(),
                snapshot.rejected().size(),
                snapshot.startedAtMillis(),
                snapshot.finishedAtMillis(),
                snapshot.rejected());
    }

    private Path resolveRoot(Path requestedRoot) throws IOException {
        Path root = requestedRoot;
        if (root == null) {
            String configured = properties.getRoot();
            if (configured == null || configured.isBlank()) {
                throw new IllegalStateException(
                        "no index root configured: set cci.root or pass the 'root' parameter");
            }
            root = Path.of(configured);
        }
        Path normalized = root.toAbsolutePath().normalize();
        Path bound = store.boundRoot();
        if (bound != null && !bound.equals(normalized)) {
            throw new IllegalStateException(
                    "index is already bound to root " + bound + "; refusing to switch to " + normalized);
        }
        if (!Files.exists(normalized)) {
            throw new IOException("index root does not exist: " + normalized);
        }
        if (!Files.isDirectory(normalized)) {
            throw new IOException("index root is not a directory: " + normalized);
        }
        if (!Files.isReadable(normalized)) {
            throw new IOException("index root is not readable: " + normalized);
        }
        return normalized;
    }

    /**
     * 以规范化相对路径为身份计算新旧快照差异。
     * 重命名表现为"旧路径删除 + 新路径新增"，由整快照替换保证不留过期内容。
     */
    private static Diff diff(IndexSnapshot previous, ScanOutcome next) {
        TreeSet<String> added = new TreeSet<>();
        TreeSet<String> updated = new TreeSet<>();
        TreeSet<String> deleted = new TreeSet<>();

        Map<String, IndexedFile> oldFiles = previous.files();
        Map<String, IndexedFile> newFiles = next.accepted();

        for (Map.Entry<String, IndexedFile> e : newFiles.entrySet()) {
            IndexedFile old = oldFiles.get(e.getKey());
            if (old == null) {
                added.add(e.getKey());
            } else if (!old.contentHash().equals(e.getValue().contentHash())) {
                updated.add(e.getKey());
            }
        }
        for (String oldPath : oldFiles.keySet()) {
            if (!newFiles.containsKey(oldPath)) {
                deleted.add(oldPath);
            }
        }
        return new Diff(List.copyOf(added), List.copyOf(updated), List.copyOf(deleted));
    }

    private record Diff(List<String> added, List<String> updated, List<String> deleted) {
    }
}
