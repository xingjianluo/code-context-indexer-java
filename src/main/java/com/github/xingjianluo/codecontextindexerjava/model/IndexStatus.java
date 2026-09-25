package com.github.xingjianluo.codecontextindexerjava.model;

import java.util.List;

/**
 * 对外暴露的索引状态：是否已初始化 + 计数 + 拒绝明细，保证状态可解释。
 */
public record IndexStatus(
        boolean initialized,
        long generation,
        String root,
        long indexedFileCount,
        long rejectedCount,
        long lastRefreshStartedAtMillis,
        long lastRefreshFinishedAtMillis,
        List<RejectedFile> rejected
) {
}
