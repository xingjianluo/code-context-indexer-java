package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.context.ContextAssembler;
import com.github.xingjianluo.codecontextindexerjava.context.ContextResult;
import com.github.xingjianluo.codecontextindexerjava.index.Chunker;
import com.github.xingjianluo.codecontextindexerjava.index.FileScanner;
import com.github.xingjianluo.codecontextindexerjava.index.IndexService;
import com.github.xingjianluo.codecontextindexerjava.index.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 上下文组装：预算内结果可复现，裁剪规则确定且可解释。 */
class ContextAssemblerTest {

    private static final Logger log = LoggerFactory.getLogger(ContextAssemblerTest.class);

    @TempDir
    Path root;

    private IndexService indexService;
    private SearchService searchService;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        IndexerProperties props = new IndexerProperties();
        indexService = new IndexService(new FileScanner(props, new Chunker(props)));
        searchService = new SearchService(indexService, props);
        assembler = new ContextAssembler();
    }

    @Test
    void assemblyIsDeterministicAndTrimmingIsExplained() throws Exception {
        // 三个文件含相同关键词，content 长度已知，便于精确计算预算
        Files.writeString(root.resolve("a.java"), "token apple");
        Files.writeString(root.resolve("b.java"), "token banana");
        Files.writeString(root.resolve("c.java"), "token cherry");
        indexService.refresh(root);

        SearchService.SearchOutcome outcome = searchService.search("token");
        assertEquals(3, outcome.hits().size());

        // 预算只够装两个片段（"token apple"=11, 分隔符=5, "token banana"=12 -> 28）
        int budget = 11 + 5 + 12;
        ContextResult r1 = assembler.assemble("token", outcome, budget);
        ContextResult r2 = assembler.assemble("token", searchService.search("token"), budget);

        log.info("[确定性] 输入=相同查询+相同预算{}; 判定依据: 两次结果必须完全相等", budget);
        assertEquals(r1, r2, "相同输入重复查询必须得到一致结论");

        log.info("[裁剪] 判定依据: included={} 应为2, trimmed={} 应为1, usedChars={} 应<=预算{}",
                r1.included().size(), r1.trimmed().size(), r1.usedChars(), budget);
        assertEquals(2, r1.included().size());
        assertEquals(1, r1.trimmed().size());
        assertTrue(r1.usedChars() <= budget);

        ContextResult.TrimmedHit trimmed = r1.trimmed().get(0);
        log.info("[裁剪依据] 被裁剪命中 path={} reason={}",
                trimmed.hit().chunk().path(), trimmed.reason());
        assertFalse(trimmed.reason().isBlank(), "裁剪必须附依据");
        // 得分相同，按路径字典序裁掉最后的 c.java
        assertEquals("c.java", trimmed.hit().chunk().path());

        // 预算为 0：全部裁剪且均有原因
        ContextResult r0 = assembler.assemble("token", outcome, 0);
        log.info("[零预算] 判定依据: included={} 应为0, trimmed={} 应为3",
                r0.included().size(), r0.trimmed().size());
        assertEquals(0, r0.included().size());
        assertEquals(3, r0.trimmed().size());
    }
}
