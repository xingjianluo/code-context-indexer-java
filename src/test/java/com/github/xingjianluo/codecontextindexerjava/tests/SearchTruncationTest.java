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

/** 命中数超过单次返回上限时的截断说明与保留规则。 */
class SearchTruncationTest {

    private static final Logger log = LoggerFactory.getLogger(SearchTruncationTest.class);

    private static final int MAX_RESULTS = 3;
    private static final int FILE_COUNT = 6;

    @TempDir
    Path root;

    private SearchService searchService;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() throws Exception {
        IndexerProperties props = new IndexerProperties();
        props.setMaxResults(MAX_RESULTS);
        IndexService indexService = new IndexService(new FileScanner(props, new Chunker(props)));
        searchService = new SearchService(indexService, props);
        assembler = new ContextAssembler();

        // 6 个文件都含关键词 alpha；file0 出现次数最多，得分应最高
        for (int i = 0; i < FILE_COUNT; i++) {
            String repeats = "alpha ".repeat(i + 1);
            Files.writeString(root.resolve("file" + i + ".java"),
                    "// " + repeats + "\nclass F" + i + " {}");
        }
        indexService.refresh(root);
        log.info("已索引 {} 个含关键词的文件，maxResults={}", FILE_COUNT, MAX_RESULTS);
    }

    @Test
    void truncationMetadataIsExposed() {
        SearchService.SearchOutcome outcome = searchService.search("alpha");
        log.info("查询[alpha] totalHits={} returnedHits={} maxResults={} truncated={}",
                outcome.totalHits(), outcome.returnedHits(), outcome.maxResults(),
                outcome.truncated());
        assertEquals(FILE_COUNT, outcome.totalHits(), "实际命中总数应为全部匹配片段数");
        assertEquals(MAX_RESULTS, outcome.returnedHits(), "本次返回条数应等于上限");
        assertTrue(outcome.truncated(), "命中数超过上限应标记为已截断");
    }

    @Test
    void retainedHitsAreTopRanked() {
        SearchService.SearchOutcome outcome = searchService.search("alpha");
        List<SearchHit> hits = outcome.hits();
        // 得分降序：file0 的 alpha 出现 1 次 …… file5 出现 6 次，应保留得分最高的 3 个
        log.info("保留命中={}",
                hits.stream().map(h -> h.chunk().path() + "@" + h.score()).toList());
        assertEquals("file5.java", hits.get(0).chunk().path());
        assertEquals("file4.java", hits.get(1).chunk().path());
        assertEquals("file3.java", hits.get(2).chunk().path());
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).score() >= hits.get(i).score(),
                    "保留的命中必须按得分降序，即最该返回的部分");
        }
    }

    @Test
    void truncationIsDeterministicAcrossRepeats() {
        SearchService.SearchOutcome first = searchService.search("alpha");
        SearchService.SearchOutcome second = searchService.search("alpha");
        List<String> firstKeys = first.hits().stream()
                .map(h -> h.chunk().path() + "#" + h.chunk().chunkIndex()).toList();
        List<String> secondKeys = second.hits().stream()
                .map(h -> h.chunk().path() + "#" + h.chunk().chunkIndex()).toList();
        log.info("重复截断查询 第一次={} 第二次={}", firstKeys, secondKeys);
        assertEquals(first.totalHits(), second.totalHits());
        assertEquals(firstKeys, secondKeys, "截断保留集合在重复查询下必须一致");
    }

    @Test
    void contextResultCarriesTruncationInfo() {
        SearchService.SearchOutcome outcome = searchService.search("alpha");
        ContextResult result = assembler.assemble("alpha", outcome, 100_000);
        log.info("ContextResult totalHits={} hitLimit={} truncatedByHitLimit={} rule={}",
                result.totalHits(), result.hitLimit(), result.truncatedByHitLimit(),
                result.retentionRule());
        assertEquals(FILE_COUNT, result.totalHits());
        assertEquals(MAX_RESULTS, result.hitLimit());
        assertTrue(result.truncatedByHitLimit());
        assertFalse(result.retentionRule().isBlank(), "必须说明截断保留/丢弃规则");
        assertEquals(MAX_RESULTS, result.included().size(), "预算充足时截断层保留的命中应全部装入");
    }

    @Test
    void noTruncationWhenUnderLimit() {
        SearchService.SearchOutcome outcome = searchService.search("file0");
        log.info("查询[file0] totalHits={} truncated={}", outcome.totalHits(), outcome.truncated());
        assertFalse(outcome.truncated(), "命中数未超上限不应标记截断");
        assertEquals(outcome.totalHits(), outcome.returnedHits());
    }
}
