package com.github.xingjianluo.codecontextindexerjava.web;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.model.IndexStatus;
import com.github.xingjianluo.codecontextindexerjava.model.RefreshResult;
import com.github.xingjianluo.codecontextindexerjava.model.SearchResult;
import com.github.xingjianluo.codecontextindexerjava.service.IndexService;
import com.github.xingjianluo.codecontextindexerjava.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 索引/检索 HTTP 协议入口。
 */
@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final IndexService indexService;
    private final SearchService searchService;
    private final IndexerProperties properties;

    public IndexController(IndexService indexService, SearchService searchService,
                           IndexerProperties properties) {
        this.indexService = indexService;
        this.searchService = searchService;
        this.properties = properties;
    }

    /** 刷新索引；root 可覆盖配置项 cci.root。 */
    @PostMapping("/refresh")
    public RefreshResult refresh(@RequestParam(required = false) String root) throws IOException {
        Path rootPath = root == null || root.isBlank() ? null : Path.of(root);
        return indexService.refresh(rootPath);
    }

    /** 查询索引状态（计数、世代、拒绝明细）。 */
    @GetMapping("/status")
    public IndexStatus status() {
        return indexService.status();
    }

    /** 在给定字符预算内检索并组装上下文片段。 */
    @GetMapping("/search")
    public SearchResult search(@RequestParam String q, @RequestParam(defaultValue = "2000") int budget) {
        return searchService.search(q, budget);
    }

    /** 返回生效的关键配置项，便于解释行为。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
                "root", properties.getRoot() == null ? "" : properties.getRoot(),
                "supportedExtensions", properties.sortedSupportedExtensions(),
                "maxFileBytes", properties.getMaxFileBytes(),
                "excludeDirs", properties.getExcludeDirs().stream().sorted().toList(),
                "snippetContextLines", properties.getSnippetContextLines(),
                "fragmentSeparator", properties.getFragmentSeparator().replace("\n", "\\n"));
    }
}
