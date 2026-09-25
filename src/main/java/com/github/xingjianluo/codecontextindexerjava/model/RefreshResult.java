package com.github.xingjianluo.codecontextindexerjava.model;

import java.util.List;

/**
 * 一次（去重后的）刷新结果，包含相对旧快照的确定性差异。
 */
public record RefreshResult(
        long generation,
        String root,
        boolean reusedInFlight,
        List<String> added,
        List<String> updated,
        List<String> deleted,
        List<RejectedFile> rejected,
        long indexedFileCount,
        long rejectedCount,
        long durationMillis
) {
}
