package com.github.xingjianluo.codecontextindexerjava.model;

import java.util.List;

/**
 * 已索引文件的不可变快照。
 *
 * @param contentHash 规范化全文内容的 SHA-256，用于稳定判断内容是否变更
 * @param lines       规范化后的文本行（不含行尾符）
 */
public record IndexedFile(
        String relativePath,
        String absolutePath,
        long size,
        long lastModified,
        String contentHash,
        List<String> lines
) {
    public IndexedFile {
        lines = List.copyOf(lines);
    }
}
