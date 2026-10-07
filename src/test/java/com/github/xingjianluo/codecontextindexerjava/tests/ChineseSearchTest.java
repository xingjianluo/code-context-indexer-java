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

/**
 * 中文与中英混合查询：中文关键词、英文关键词、中英混排查询都必须
 * 命中确实包含对应内容的片段；同一快照 + 同一查询重复执行结果逐条一致。
 */
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

        Files.writeString(root.resolve("LoginService.java"),
                "// 用户登录入口，校验用户名和密码\n"
                        + "class LoginService {\n"
                        + "    // 登录失败时记录日志\n"
                        + "    void login() {}\n"
                        + "}\n");
        Files.writeString(root.resolve("CacheService.java"),
                "// 缓存刷新策略，定时清理过期缓存\n"
                        + "class CacheService {\n"
                        + "    void refresh() {}\n"
                        + "}\n");
        Files.writeString(root.resolve("README.md"),
                "# 支付模块\n\n支付网关对接文档，支持退款与对账。\n");
        indexService.refresh(root);
    }

    @Test
    void chineseOnlyQueryHitsChineseContent() {
        SearchService.SearchOutcome outcome = searchService.search("登录");
        log.info("[中文查询] 输入=q='登录'; 判定依据: 命中数={} 应>0, 首命中 path={} 应含 LoginService",
                outcome.totalHits(),
                outcome.hits().isEmpty() ? "<none>" : outcome.hits().get(0).chunk().path());
        assertFalse(outcome.hits().isEmpty(), "中文关键词必须命中包含该词的片段");
        assertTrue(outcome.hits().stream()
                        .anyMatch(h -> h.chunk().content().contains("登录")),
                "命中片段必须确实包含中文关键词");
        assertEquals("LoginService.java", outcome.hits().get(0).chunk().path());
    }

    @Test
    void chinesePhraseQueryRanksByOccurrence() {
        // "缓存" 在 CacheService.java 出现 2 次（注释），应排在最前
        SearchService.SearchOutcome outcome = searchService.search("缓存刷新");
        log.info("[中文短语] 输入=q='缓存刷新'; 判定依据: 首命中 path={} 应为 CacheService.java",
                outcome.hits().isEmpty() ? "<none>" : outcome.hits().get(0).chunk().path());
        assertFalse(outcome.hits().isEmpty());
        assertEquals("CacheService.java", outcome.hits().get(0).chunk().path());
    }

    @Test
    void mixedChineseEnglishQueryHits() {
        SearchService.SearchOutcome outcome = searchService.search("登录 login");
        log.info("[中英混排] 输入=q='登录 login'; 判定依据: 命中数={} 应>0, 首命中 path={} 应为 LoginService.java",
                outcome.totalHits(),
                outcome.hits().isEmpty() ? "<none>" : outcome.hits().get(0).chunk().path());
        assertFalse(outcome.hits().isEmpty(), "中英混合查询必须命中");
        assertEquals("LoginService.java", outcome.hits().get(0).chunk().path());
        assertTrue(outcome.hits().stream()
                .anyMatch(h -> h.chunk().content().contains("登录")
                        && h.chunk().content().toLowerCase().contains("login")));
    }

    @Test
    void englishQueryStillWorks() {
        SearchService.SearchOutcome outcome = searchService.search("refresh");
        log.info("[英文回归] 输入=q='refresh'; 判定依据: 命中数={} 应>0 且首命中为 CacheService.java",
                outcome.totalHits());
        assertFalse(outcome.hits().isEmpty());
        assertEquals("CacheService.java", outcome.hits().get(0).chunk().path());
    }

    @Test
    void repeatedIdenticalQueryIsDeterministic() {
        SearchService.SearchOutcome first = searchService.search("登录 缓存 login");
        SearchService.SearchOutcome second = searchService.search("登录 缓存 login");
        List<String> firstKeys = first.hits().stream()
                .map(this::hitKey).toList();
        List<String> secondKeys = second.hits().stream()
                .map(this::hitKey).toList();
        log.info("[确定性] 输入=同一查询执行两次; 判定依据: 两次命中序列必须逐条一致, 第一次={}, 第二次={}",
                firstKeys, secondKeys);
        assertEquals(first.snapshotVersion(), second.snapshotVersion());
        assertEquals(first.totalHits(), second.totalHits());
        assertEquals(firstKeys, secondKeys, "相同快照 + 相同查询的结果与排序必须一致");
    }

    @Test
    void singleChineseCharQueryHits() {
        SearchService.SearchOutcome outcome = searchService.search("退");
        log.info("[单字查询] 输入=q='退'; 判定依据: 命中数={} 应>0 且命中 README.md",
                outcome.totalHits());
        assertFalse(outcome.hits().isEmpty());
        assertTrue(outcome.hits().stream()
                .anyMatch(h -> h.chunk().path().equals("README.md")));
    }

    private String hitKey(SearchHit h) {
        return h.chunk().path() + "#" + h.chunk().chunkIndex() + "@" + h.score();
    }
}
