package com.github.xingjianluo.codecontextindexerjava.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 索引在某一时刻的不可变快照。检索永远只读取完整快照，不会观察到刷新中间态。
 */
public record IndexSnapshot(
        Path root,
        long generation,
        long startedAtMillis,
        long finishedAtMillis,
        Map<String, IndexedFile> files,
        List<RejectedFile> rejected
) {
    public IndexSnapshot {
        files = Map.copyOf(files);
        rejected = List.copyOf(rejected);
    }

    /** 尚未建立任何索引时的空快照标记。 */
    public static IndexSnapshot empty(Path root) {
        return new IndexSnapshot(root, 0L, 0L, 0L, Map.of(), List.of());
    }
}
