package com.github.xingjianluo.codecontextindexerjava.index;

import com.github.xingjianluo.codecontextindexerjava.model.IndexSnapshot;
import com.github.xingjianluo.codecontextindexerjava.model.IndexedFile;
import com.github.xingjianluo.codecontextindexerjava.model.RejectedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 索引核心服务。
 *
 * 一致性协议：
 * 1. 刷新在 refreshLock 下串行执行，并发/重复刷新彼此排队；
 * 2. 每次刷新基于磁盘当前状态完整构建新快照（纯函数，天然幂等）；
 * 3. 新快照通过 AtomicReference 一次性发布，检索只会看到
 *    旧快照或新快照整体，绝不观察中间状态；
 * 4. 所有计数均从快照派生，不存在独立计数器，因此不会漂移。
 */
@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    private final FileScanner scanner;
    private final java.util.concurrent.atomic.AtomicReference<IndexSnapshot> snapshot =
            new java.util.concurrent.atomic.AtomicReference<>(IndexSnapshot.empty());
    private final ReentrantLock refreshLock = new ReentrantLock(true);

    public IndexService(FileScanner scanner) {
        this.scanner = scanner;
    }

    /** 当前已发布快照，检索专用入口。 */
    public IndexSnapshot currentSnapshot() {
        return snapshot.get();
    }

    /**
     * 刷新指定目录的索引。串行执行；完成后返回相对上一快照的差异报告。
     *
     * <p>rootDir 先做参数校验（不存在 / 不是目录 / 不可读分别给出可区分原因），
     * 校验或扫描失败时抛出异常，已发布快照保持原样，检索结果不受影响。
     *
     * @throws InvalidRootDirException rootDir 不存在、不是目录或不可读
     */
    public RefreshReport refresh(Path rootDir) {
        validateRootDir(rootDir);
        refreshLock.lock();
        try {
            IndexSnapshot previous = snapshot.get();
            FileScanner.ScanResult result = scanner.scan(rootDir);

            Map<String, IndexedFile> indexed = new TreeMap<>();
            for (IndexedFile f : result.indexed()) {
                indexed.put(f.path(), f);
            }
            Map<String, RejectedFile> rejected = new LinkedHashMap<>();
            for (RejectedFile r : result.rejected()) {
                rejected.put(r.path(), r);
            }

            IndexSnapshot next = new IndexSnapshot(
                    previous.version() + 1, rootDir.toString(), Instant.now(), indexed, rejected);
            snapshot.set(next);

            int added = 0, modified = 0, unchanged = 0;
            for (Map.Entry<String, IndexedFile> e : indexed.entrySet()) {
                IndexedFile old = previous.indexedFiles().get(e.getKey());
                if (old == null) {
                    added++;
                } else if (!old.contentHash().equals(e.getValue().contentHash())) {
                    modified++;
                } else {
                    unchanged++;
                }
            }
            Map<String, IndexedFile> oldIndexed = previous.indexedFiles();
            int removed = 0;
            for (String oldPath : oldIndexed.keySet()) {
                if (!indexed.containsKey(oldPath)) {
                    removed++;
                }
            }
            RefreshReport report = new RefreshReport(
                    next.version(), next.rootDir(), added, modified, removed, unchanged,
                    next.indexedCount(), next.rejectedCount(),
                    java.util.List.copyOf(rejected.values()));
            log.info("索引刷新完成 version={} root={} added={} modified={} removed={} unchanged={} "
                            + "indexedTotal={} rejectedTotal={}",
                    report.version(), report.rootDir(), added, modified, removed, unchanged,
                    report.indexedTotal(), report.rejectedTotal());
            return report;
        } finally {
            refreshLock.unlock();
        }
    }

    /** 校验 rootDir：不存在、不是目录、不可读三种情况分别抛出可区分原因。 */
    private static void validateRootDir(Path rootDir) {
        if (!Files.exists(rootDir)) {
            throw new InvalidRootDirException(InvalidRootDirException.Reason.NOT_FOUND,
                    rootDir.toString(), "目录不存在: " + rootDir);
        }
        if (!Files.isDirectory(rootDir)) {
            throw new InvalidRootDirException(InvalidRootDirException.Reason.NOT_A_DIRECTORY,
                    rootDir.toString(), "路径不是目录: " + rootDir);
        }
        if (!Files.isReadable(rootDir)) {
            throw new InvalidRootDirException(InvalidRootDirException.Reason.UNREADABLE,
                    rootDir.toString(), "目录不可读: " + rootDir);
        }
    }
}
