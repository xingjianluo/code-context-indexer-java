package com.github.xingjianluo.codecontextindexerjava.index;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 索引相关配置项，前缀 indexer。 */
@ConfigurationProperties(prefix = "indexer")
public class IndexerProperties {

    /** 支持索引的扩展名（小写、不含点）。 */
    private List<String> supportedExtensions = List.of(
            "java", "xml", "properties", "yml", "yaml", "md", "txt", "json", "sql", "gradle");

    /** 单文件大小上限（字节），超过则拒绝索引。 */
    private long maxFileSizeBytes = 1_000_000L;

    /** 单个片段最大行数。 */
    private int chunkMaxLines = 40;

    /** 单个片段最大字符数。 */
    private int chunkMaxChars = 2000;

    /** 检索默认返回的最大命中数。 */
    private int maxResults = 50;

    public List<String> getSupportedExtensions() {
        return supportedExtensions;
    }

    public void setSupportedExtensions(List<String> supportedExtensions) {
        this.supportedExtensions = supportedExtensions;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public int getChunkMaxLines() {
        return chunkMaxLines;
    }

    public void setChunkMaxLines(int chunkMaxLines) {
        this.chunkMaxLines = chunkMaxLines;
    }

    public int getChunkMaxChars() {
        return chunkMaxChars;
    }

    public void setChunkMaxChars(int chunkMaxChars) {
        this.chunkMaxChars = chunkMaxChars;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }
}
