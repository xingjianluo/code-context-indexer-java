package com.github.xingjianluo.codecontextindexerjava.tests;

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

/** 中文、英文及中英混排查询的命中与确定性。 */
class ChineseSearchTest {

    private static final Logger log = LoggerFactory.getLogger(ChineseSearchTest.class);

    @TempDir
    Path root;

    private SearchService searchService;

    @BeforeEach
    void setUp() throws Exception {
        IndexerProperties props = new IndexerProperties();
        IndexService indexService = new IndexService(new FileScanner(props, new Chunker(props)));
        searchService = new SearchService(indexService, props);

        Files.writeString(root.resolve("注释.java"),
                "// 用户登录模块的鉴权逻辑\nclass Auth { /* 校验令牌 */ }");
        Files.writeString(root.resolve("说明.md"),
                "# 部署指南\n本文档说明如何在本地构建索引并检索代码上下文。");
        Files.writeString(root.resolve("Mixed.java"),
                "// tokenizer 负责中文分词\nclass Tokenizer { String locale = \"zh_CN\"; }");
        indexService.refresh(root);
        log.info("测试数据已索引 root={}", root);
    }

    @Test
    void chineseQueryHitsChineseContent() {
        SearchService.SearchOutcome outcome = searchService.search("鉴权");
        log.info("中文查询[鉴权] totalHits={} 命中路径={}",
                outcome.totalHits(),
                outcome.hits().stream().map(h -> h.chunk().path()).toList());
        assertFalse(outcome.hits().isEmpty(), "中文查询应命中含对应内容的片段");
        assertTrue(outcome.hits().stream()
                        .anyMatch(h -> h.chunk().content().contains("鉴权")),
                "命中片段应确实包含查询内容");
    }

    @Test
    void singleChineseCharQueryHits() {
        SearchService.SearchOutcome outcome = searchService.search("索");
        log.info("单字查询[索] totalHits={}", outcome.totalHits());
        assertFalse(outcome.hits().isEmpty(), "单个中文字也应能命中");
        assertTrue(outcome.hits().stream()
                .anyMatch(h -> h.chunk().content().contains("索")));
    }

    @Test
    void mixedChineseEnglishQueryHitsBoth() {
        SearchService.SearchOutcome outcome = searchService.search("tokenizer 中文分词");
        log.info("混排查询[tokenizer 中文分词] totalHits={} 首命中={}",
                outcome.totalHits(),
                outcome.hits().isEmpty() ? "无" : outcome.hits().get(0).chunk().path());
        assertFalse(outcome.hits().isEmpty(), "中英混排查询应有命中");
        SearchHit top = outcome.hits().get(0);
        assertTrue(top.chunk().content().contains("tokenizer")
                        && top.chunk().content().contains("中文分词"),
                "排名最前的片段应同时包含英文词与中文内容");
    }

    @Test
    void repeatedQueriesAreDeterministic() {
        String query = "本地构建索引";
        SearchService.SearchOutcome first = searchService.search(query);
        SearchService.SearchOutcome second = searchService.search(query);
        List<String> firstKeys = first.hits().stream()
                .map(h -> h.chunk().path() + "#" + h.chunk().chunkIndex() + "@" + h.score())
                .toList();
        List<String> secondKeys = second.hits().stream()
                .map(h -> h.chunk().path() + "#" + h.chunk().chunkIndex() + "@" + h.score())
                .toList();
        log.info("重复查询[{}] 第一次={} 第二次={}", query, firstKeys, secondKeys);
        assertEquals(first.totalHits(), second.totalHits(), "重复查询命中总数应一致");
        assertEquals(firstKeys, secondKeys, "重复查询结果与排序应逐条一致");
    }
}
