package com.github.xingjianluo.codecontextindexerjava.search;

import com.github.xingjianluo.codecontextindexerjava.model.IndexedChunk;

/** 一条检索命中：片段 + 得分。 */
public record SearchHit(IndexedChunk chunk, double score) {
}
