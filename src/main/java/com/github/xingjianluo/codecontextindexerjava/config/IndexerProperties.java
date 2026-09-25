package com.github.xingjianluo.codecontextindexerjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * 索引器配置项，前缀 cci（code-context-indexer）。
 */
@ConfigurationProperties(prefix = "cci")
public class IndexerProperties {

    /** 被索引的本地代码根目录；为空时必须在刷新请求中显式指定。 */
    private String root;

    /** 允许索引的文本文件扩展名（小写、不含点）。 */
    private Set<String> supportedExtensions = Set.of(
            "java", "txt", "md", "properties", "xml", "yml", "yaml",
            "json", "js", "ts", "css", "html", "sql", "sh", "csv");

    /** 单个文件允许的最大字节数，超过则以 TOO_LARGE 拒绝。 */
    private long maxFileBytes = 1_048_576L;

    /** 遍历时直接跳过的目录名（不产生拒绝记录，属于配置策略而非文件错误）。 */
    private Set<String> excludeDirs = Set.of(".git", "target", "build", "node_modules", ".idea");

    /** 上下文片段中命中行上下各取多少行。 */
    private int snippetContextLines = 2;

    /** 片段之间连接时的分隔符。 */
    private String fragmentSeparator = "\n---\n";

    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }

    public Set<String> getSupportedExtensions() { return supportedExtensions; }
    public void setSupportedExtensions(Set<String> supportedExtensions) { this.supportedExtensions = supportedExtensions; }

    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }

    public Set<String> getExcludeDirs() { return excludeDirs; }
    public void setExcludeDirs(Set<String> excludeDirs) { this.excludeDirs = excludeDirs; }

    public int getSnippetContextLines() { return snippetContextLines; }
    public void setSnippetContextLines(int snippetContextLines) { this.snippetContextLines = snippetContextLines; }

    public String getFragmentSeparator() { return fragmentSeparator; }
    public void setFragmentSeparator(String fragmentSeparator) { this.fragmentSeparator = fragmentSeparator; }

    /** 派生属性：扩展名集合的不可变列表视图，便于文档/日志展示。 */
    public List<String> sortedSupportedExtensions() {
        return supportedExtensions.stream().sorted().toList();
    }
}
