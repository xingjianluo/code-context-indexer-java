package com.github.xingjianluo.codecontextindexerjava.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 不可变索引快照。刷新过程完整构建新快照后原子替换发布，
 * 检索方永远只能看到某个完整快照，不会观察到中间状态。
 */
public final class IndexSnapshot {

    private static final IndexSnapshot EMPTY = new IndexSnapshot(
            0L, "", Instant.EPOCH, Map.of(), Map.of());

    private final long version;
    private final String rootDir;
    private final Instant builtAt;
    private final Map<String, IndexedFile> indexedFiles;
    private final Map<String, RejectedFile> rejectedFiles;

    public IndexSnapshot(long version, String rootDir, Instant builtAt,
                         Map<String, IndexedFile> indexedFiles,
                         Map<String, RejectedFile> rejectedFiles) {
        this.version = version;
        this.rootDir = rootDir;
        this.builtAt = builtAt;
        this.indexedFiles = Map.copyOf(indexedFiles);
        this.rejectedFiles = Map.copyOf(rejectedFiles);
    }

    public static IndexSnapshot empty() {
        return EMPTY;
    }

    public long version() {
        return version;
    }

    public String rootDir() {
        return rootDir;
    }

    public Instant builtAt() {
        return builtAt;
    }

    public Map<String, IndexedFile> indexedFiles() {
        return indexedFiles;
    }

    public Map<String, RejectedFile> rejectedFiles() {
        return rejectedFiles;
    }

    public Optional<IndexedFile> indexedFile(String path) {
        return Optional.ofNullable(indexedFiles.get(path));
    }

    public int indexedCount() {
        return indexedFiles.size();
    }

    public int rejectedCount() {
        return rejectedFiles.size();
    }

    /** 全部片段，按 (path, chunkIndex) 字典序排列，保证遍历顺序确定。 */
    public List<IndexedChunk> allChunks() {
        return indexedFiles.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(e -> e.getValue().chunks().stream())
                .toList();
    }
}
