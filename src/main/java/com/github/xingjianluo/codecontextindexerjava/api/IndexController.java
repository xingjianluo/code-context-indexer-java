package com.github.xingjianluo.codecontextindexerjava.api;

import com.github.xingjianluo.codecontextindexerjava.context.ContextAssembler;
import com.github.xingjianluo.codecontextindexerjava.context.ContextResult;
import com.github.xingjianluo.codecontextindexerjava.index.IndexService;
import com.github.xingjianluo.codecontextindexerjava.index.RefreshReport;
import com.github.xingjianluo.codecontextindexerjava.model.IndexSnapshot;
import com.github.xingjianluo.codecontextindexerjava.model.RejectedFile;
import com.github.xingjianluo.codecontextindexerjava.search.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 索引与检索 HTTP 接口。 */
@RestController
@RequestMapping("/api")
public class IndexController {

    private final IndexService indexService;
    private final SearchService searchService;
    private final ContextAssembler contextAssembler;

    public IndexController(IndexService indexService, SearchService searchService,
                           ContextAssembler contextAssembler) {
        this.indexService = indexService;
        this.searchService = searchService;
        this.contextAssembler = contextAssembler;
    }

    /** 刷新指定目录的索引，返回差异报告（含被拒绝文件及原因）。 */
    @PostMapping("/index/refresh")
    public RefreshReport refresh(@RequestParam String rootDir) {
        return indexService.refresh(Path.of(rootDir));
    }

    /** 当前快照状态：版本、计数与被拒绝明细。 */
    @GetMapping("/index/status")
    public Map<String, Object> status() {
        IndexSnapshot s = indexService.currentSnapshot();
        return Map.of(
                "version", s.version(),
                "rootDir", s.rootDir(),
                "builtAt", s.builtAt().toString(),
                "indexedCount", s.indexedCount(),
                "rejectedCount", s.rejectedCount(),
                "rejected", List.copyOf(s.rejectedFiles().values()));
    }

    /** 检索并按预算组装上下文，返回可复现结果与裁剪依据。 */
    @GetMapping("/search")
    public ContextResult search(@RequestParam String q,
                                @RequestParam(defaultValue = "4000") int budget) {
        SearchService.SearchOutcome outcome = searchService.search(q);
        return contextAssembler.assemble(q, outcome, budget);
    }
}
