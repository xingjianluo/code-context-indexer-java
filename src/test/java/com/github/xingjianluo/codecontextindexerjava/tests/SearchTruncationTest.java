package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.context.ContextAssembler;
import com.github.xingjianluo.codecontextindexerjava.context.ContextResult;
import com.github.xingjianluo.codecontextindexerjava.index.Chunker;
import com.github.xingjianluo.codecontextindexerjava.index.FileScanner;
import com.github.xingjianluo.codecontextindexerjava.index.IndexService;
import com.github.xingjianluo.codecontextindexerjava.index.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.search.SearchHit;
import com.github.xingjianluo.codecontextindexerjava.search.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 命中数超过单次返回上限（indexer.max-results）时的截断说明：
 * 调用方必须能看到实际命中总数、本次返回条数、是否截断及截断规则；
 * 保留下来的必须是排名前 N 的命中，重复查询结论一致。
 */
class SearchTruncationTest {

    private static final Logger log = LoggerFactory.getLogger(SearchTruncationTest.class);

    private static final int LIMIT = 3;

    @TempDir
    Path root;

    private SearchService limitedSearch;
    private SearchService unlimitedSearch;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() throws Exception {
        IndexerProperties limited = new IndexerProperties();
        limited.setMaxResults(LIMIT);
        IndexerProperties unlimited = new IndexerProperties();
        unlimited.setMaxResults(1000);

        IndexService indexService = new IndexService(
                new FileScanner(limited, new Chunker(limited)));
        indexService.refresh(rootOfSixFiles());

        limitedSearch = new SearchService(indexService, limited);
        unlimitedSearch = new SearchService(indexService, unlimited);
        assembler = new ContextAssembler();
    }

    /** 六个文件都含关键词 alpha，但出现次数不同，产生确定排名。 */
    private Path rootOfSixFiles() throws Exception {
        for (int i = 1; i <= 6; i++) {
            // 文件 i 中 alpha 出现 i 次，得分互不相同，排名完全确定
            Files.writeString(root.resolve("f" + i + ".txt"), "alpha ".repeat(i).trim());
        }
        // 仅两个文件含独特词 raretoken，用于构造不触发截断的查询
        Files.writeString(root.resolve("r1.txt"), "raretoken 独特内容一");
        Files.writeString(root.resolve("r2.txt"), "raretoken 独特内容二");
        return root;
    }

    @Test
    void truncationMetadataIsExposed() {
        SearchService.SearchOutcome outcome = limitedSearch.search("alpha");
        log.info("[截断说明] 输入=q='alpha', maxResults={}; 判定依据: totalHits={} 应为6, "
                        + "returnedHits={} 应为{}, truncated={} 应为true",
                LIMIT, outcome.totalHits(), outcome.returnedHits(), LIMIT, outcome.truncated());
        assertEquals(6, outcome.totalHits(), "必须暴露实际命中总数");
        assertEquals(LIMIT, outcome.returnedHits(), "返回条数必须等于上限");
        assertEquals(LIMIT, outcome.hitLimit());
        assertTrue(outcome.truncated(), "必须明确标记发生了截断");
    }

    @Test
    void keptHitsAreTopRanked() {
        List<SearchHit> kept = limitedSearch.search("alpha").hits();
        List<SearchHit> full = unlimitedSearch.search("alpha").hits();
        List<String> keptKeys = kept.stream().map(this::key).toList();
        List<String> expectedTop = full.stream().limit(LIMIT).map(this::key).toList();
        log.info("[保留规则] 判定依据: 保留命中={} 必须等于完整排名的前{}名={}",
                keptKeys, LIMIT, expectedTop);
        assertEquals(expectedTop, keptKeys, "保留下来的必须是排名最前的命中");
        // 得分必须单调不升（排名最靠前的那部分）
        for (int i = 1; i < kept.size(); i++) {
            assertTrue(kept.get(i - 1).score() >= kept.get(i).score());
        }
    }

    @Test
    void repeatedTruncatedQueryIsConsistent() {
        SearchService.SearchOutcome first = limitedSearch.search("alpha");
        SearchService.SearchOutcome second = limitedSearch.search("alpha");
        log.info("[重复一致] 判定依据: 两次 totalHits/returnedHits/truncated 及命中序列必须一致");
        assertEquals(first.totalHits(), second.totalHits());
        assertEquals(first.returnedHits(), second.returnedHits());
        assertEquals(first.truncated(), second.truncated());
        assertEquals(first.hits().stream().map(this::key).toList(),
                second.hits().stream().map(this::key).toList());
    }

    @Test
    void belowLimitIsNotTruncated() {
        SearchService.SearchOutcome rare = limitedSearch.search("raretoken");
        log.info("[未截断] 输入=q='raretoken'; 判定依据: totalHits={} 应为2(<=上限{}), "
                        + "truncated={} 应为false, returnedHits={} 应等于 totalHits",
                rare.totalHits(), LIMIT, rare.truncated(), rare.returnedHits());
        assertEquals(2, rare.totalHits());
        assertFalse(rare.truncated(), "命中数未超限时不得标记截断");
        assertEquals(rare.totalHits(), rare.returnedHits());
    }

    @Test
    void contextResultCarriesTruncationInfo() {
        SearchService.SearchOutcome outcome = limitedSearch.search("alpha");
        ContextResult result = assembler.assemble("alpha", outcome, 100_000);
        log.info("[接口层截断] 判定依据: totalHits={} 应为6, returnedHits={} 应为{}, "
                        + "truncatedByHitLimit={} 应为true, hitLimitRule='{}'",
                result.totalHits(), result.returnedHits(), LIMIT,
                result.truncatedByHitLimit(), result.hitLimitRule());
        assertEquals(6, result.totalHits());
        assertEquals(LIMIT, result.returnedHits());
        assertEquals(LIMIT, result.hitLimit());
        assertTrue(result.truncatedByHitLimit());
        assertFalse(result.hitLimitRule().isBlank(), "必须给出截断保留/丢弃规则说明");
        assertEquals(result.included().size() + result.trimmed().size(), result.returnedHits());
    }

    private String key(SearchHit h) {
        return h.chunk().path() + "#" + h.chunk().chunkIndex();
    }
}
