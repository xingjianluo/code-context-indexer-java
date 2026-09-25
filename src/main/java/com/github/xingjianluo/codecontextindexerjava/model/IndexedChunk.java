package com.github.xingjianluo.codecontextindexerjava.model;

/** 文件切分后的一个内容片段。 */
public record IndexedChunk(String path, int chunkIndex, int startLine, int endLine, String content) {
}
