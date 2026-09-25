package com.github.xingjianluo.codecontextindexerjava.model;

/**
 * 被拒绝索引的文件记录：相对路径 + 可区分原因 + 细节说明。
 */
public record RejectedFile(
        String relativePath,
        String absolutePath,
        RejectReason reason,
        String detail
) {
}
