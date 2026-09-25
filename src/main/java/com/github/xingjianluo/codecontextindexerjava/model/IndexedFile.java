package com.github.xingjianluo.codecontextindexerjava.model;

import java.util.List;

/** 成功索引的文件：内容哈希 + 有序片段列表。 */
public record IndexedFile(String path, long sizeBytes, String contentHash, List<IndexedChunk> chunks) {
}
