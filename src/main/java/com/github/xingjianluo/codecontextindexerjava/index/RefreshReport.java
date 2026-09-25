package com.github.xingjianluo.codecontextindexerjava.index;

import com.github.xingjianluo.codecontextindexerjava.model.RejectedFile;

import java.util.List;

/**
 * 一次刷新的可解释报告：相对上一快照的增删改统计与被拒绝文件明细。
 */
public record RefreshReport(
        long version,
        String rootDir,
        int added,
        int modified,
        int removed,
        int unchanged,
        int indexedTotal,
        int rejectedTotal,
        List<RejectedFile> rejected) {
}
