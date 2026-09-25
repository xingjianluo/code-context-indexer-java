package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InMemoryIndexStore;
import com.github.xingjianluo.codecontextindexerjava.model.IndexStatus;
import com.github.xingjianluo.codecontextindexerjava.model.SearchResult;
import com.github.xingjianluo.codecontextindexerjava.scanner.DirectoryScanner;
import com.github.xingjianluo.codecontextindexerjava.service.IndexService;
import com.github.xingjianluo.codecontextindexerjava.service.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文片段组装、预算确定性裁剪与可复现性。
 */
class ContextBudgetTest {

    private record Harness(IndexService index, SearchService search) {
    }

    private Harness newHarness(Path root, IndexerProperties props) throws Exception {
        props.setRoot(root.toString());
        InMemoryIndexStore store = new InMemoryIndexStore();
        DirectoryScanner scanner = new DirectoryScanner(props);
        IndexService index = new IndexService(props, scanner, store);
        index.refresh(null);
        return new Harness(index, new SearchService(store, props));
    }

    @Test
    void reproducibleAndDeterministicBudgetPacking(@TempDir Path root) throws Exception {
        IndexerProperties props = new IndexerProperties();
        props.setSnippetContextLines(0); // 只取命中行，便于精确计算预算
        Files.writeString(root.resolve("A.java"), "alpha token\n");
        Files.writeString(root.resolve("B.java"), "beta token\n");
        Files.createDirectories(root.resolve("z"));
        Files.writeString(root.resolve("z/C.java"), "gamma token\n");
        Harness h = newHarness(root, props);

        // 充足预算：3 个文件都返回，未裁剪。
        SearchResult big = h.search().search("token", 5000);
        TestJudge.judge("full-budget-hits", "query='token', budget=5000",
                "totalHits=3, fragments=3, truncated=false",
                "totalHits=" + big.totalHits() + ", fragments=" + big.fragments().size()
                        + ", truncated=" + big.truncated());
        assertEquals(3, big.totalHits());
        assertEquals(3, big.fragments().size());
        assertFalse(big.truncated());

        // 路径平分时的确定性顺序：字典序 A.java, B.java, z/C.java。
        assertEquals("A.java", big.fragments().get(0).relativePath());
        assertEquals("B.java", big.fragments().get(1).relativePath());
        assertEquals("z/C.java", big.fragments().get(2).relativePath());

        // 预算只够第一个片段（含 header 按码点计）：第 1 个完整装入，
        // 第 2 个（连同分隔符）装不下 → 按规则停止并标记裁剪。
        int firstCost = cost(big.fragments().get(0).text());
        SearchResult tight = h.search().search("token", firstCost);
        TestJudge.info("exact-one-fragment-budget", "budget=" + firstCost,
                "第1个片段完整装入，第2个装不下；断言 fragments=1, truncated=true, returnedChars=预算",
                "fragments=" + tight.fragments().size()
                        + ", truncated=" + tight.truncated()
                        + ", returnedChars=" + tight.returnedChars());
        assertEquals(1, tight.fragments().size());
        assertEquals(firstCost, tight.returnedChars());
        assertTrue(tight.truncated(), "第 2 个片段被省略，必须标记裁剪并说明依据");
        assertFalse(tight.truncationRule().isBlank());

        // 预算差一个码点：第一个片段装不下 → 0 片段且明确裁剪，规则可解释。
        SearchResult tooSmall = h.search().search("token", firstCost - 1);
        TestJudge.info("one-codepoint-short", "budget=" + (firstCost - 1),
                "首个片段即装不下；断言 fragments=0, truncated=true, 裁剪规则非空",
                "fragments=" + tooSmall.fragments().size()
                        + ", truncated=" + tooSmall.truncated()
                        + ", ruleBlank=" + tooSmall.truncationRule().isBlank());
        assertEquals(0, tooSmall.fragments().size());
        assertTrue(tooSmall.truncated());
        assertFalse(tooSmall.truncationRule().isBlank(), "裁剪依据必须说明");

        // 可复现：同样的输入执行两次，结果逐字段一致。
        SearchResult rerun1 = h.search().search("token", 1000);
        SearchResult rerun2 = h.search().search("token", 1000);
        TestJudge.judge("reproducibility", "query='token', budget=1000 x2",
                rerun1.toString(), rerun2.toString());
        assertEquals(rerun1.toString(), rerun2.toString(), "相同输入必须得到一致结论");

        // 片段包含命中行号，可解释命中位置。
        TestJudge.info("matched-lines", "A.java",
                "片段须报告 1-based 命中行号",
                big.fragments().get(0).matchedLines());
        assertEquals(java.util.List.of(1), big.fragments().get(0).matchedLines());
    }

    @Test
    void contextWindowAndChangedFileChangeResults(@TempDir Path root) throws Exception {
        IndexerProperties props = new IndexerProperties();
        props.setSnippetContextLines(1);
        Files.writeString(root.resolve("Doc.md"),
                "line0\nneedle appears here\nline2\n");
        Harness h = newHarness(root, props);

        SearchResult r = h.search().search("needle", 5000);
        String text = r.fragments().get(0).text();
        boolean allThree = text.contains("line0") && text.contains("needle") && text.contains("line2");
        TestJudge.judge("context-window", "Doc.md 命中第 2 行，半径 1",
                "true", String.valueOf(allThree));
        assertTrue(text.contains("line0") && text.contains("needle") && text.contains("line2"));

        // 刷新移除命中词后，检索结论必须与新索引一致（不残留）。
        Files.writeString(root.resolve("Doc.md"), "line0\nnothing here\nline2\n");
        h.index().refresh(null);
        SearchResult after = h.search().search("needle", 5000);
        TestJudge.judge("stale-content-gone", "Doc.md 删除 needle 后刷新",
                "totalHits=0", "totalHits=" + after.totalHits());
        assertEquals(0, after.totalHits());
        IndexStatus status = h.index().status();
        TestJudge.info("status-after-change", "Doc.md",
                "状态可解释：世代、计数",
                "generation=" + status.generation() + ", indexed=" + status.indexedFileCount());
    }

    private static int cost(String text) {
        return text.codePointCount(0, text.length());
    }
}
