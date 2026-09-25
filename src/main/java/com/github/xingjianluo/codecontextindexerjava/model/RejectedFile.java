package com.github.xingjianluo.codecontextindexerjava.model;

/** 被拒绝索引的文件及其可区分原因。 */
public record RejectedFile(String path, RejectReason reason, String detail) {
}
