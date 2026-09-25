package com.github.xingjianluo.codecontextindexerjava.service;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.index.InMemoryIndexStore;
import com.github.xingjianluo.codecontextindexerjava.model.IndexSnapshot;
import com.github.xingjianluo.codecontextindexerjava.model.IndexedFile;
import com.github.xingjianluo.codecontextindexerjava.model.SearchResult;
import com.github.xingjianluo.codecontextindexerjava.util.CharBuf;
import com.github.xingjianluo.codecontextindexerjava.util.TextTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * 上下文检索服务。
 *
 * <h3>确定性打分规则（相同输入永远得到相同结论）</h3>
 * <ol>
 *   <li>查询以 {@link TextTokenizer} 分词；文件按行分词后统计匹配；</li>
 *   <li>每个查询 term 在行内容中命中一次得 1 分（同一行对同一 term 只计一次）；</li>
 *   <li>term 在文件名（小写）中出现额外 +2 分；</li>
 *   <li>命中按分数降序排序，平分时按相对路径字典序升序，结果稳定。</li>
 * </ol>
 *
 * <h3>片段与预算</h3>
 * <ul>
 *   <li>每个命中行取前后 {@code cci.snippet-context-lines} 行作为窗口，
 *       同一文件内重叠/相邻窗口合并为一个 {@link SearchResult.ContextFragment}；</li>
 *   <li>片段按（分数降序, 路径升序, 起始行升序）排列，
 *       依次装入预算（按 Unicode 码点计数），放不下即停止；</li>
 *   <li>预算不足以容纳全部片段时 {@code truncated=true}，
 *       {@code truncationRule} 说明裁剪依据；重复查询输出完全一致。</li>
 * </ul>
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    static final String TRUNCATION_RULE =
            "fragments are ordered by (score desc, path asc, firstLine asc) and packed in that "
                    + "order into the character budget measured in Unicode code points; packing "
                    + "stops at the first fragment that does not fully fit, and all later fragments "
                    + "(plus any remaining files with hits) are omitted";

    private final InMemoryIndexStore store;
    private final IndexerProperties properties;

    public SearchService(InMemoryIndexStore store, IndexerProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    public SearchResult search(String query, int budgetChars) {
        if (budgetChars < 0) {
            throw new IllegalArgumentException("budget must be >= 0, got " + budgetChars);
        }
        List<String> terms = TextTokenizer.tokenize(query);

        // 先抓取当前已发布快照；整个查询只基于这一个不可变快照，天然读到一致状态。
        IndexSnapshot snapshot = store.current();

        if (terms.isEmpty()) {
            log.info("empty query after tokenization: raw='{}', generation={}", query, snapshot.generation());
            return new SearchResult(query, budgetChars, snapshot.generation(),
                    0, List.of(), 0, false, "");
        }

        List<FileMatch> matches = new ArrayList<>();
        for (IndexedFile file : snapshot.files().values()) {
            FileMatch match = scoreFile(file, terms);
            if (match != null) {
                matches.add(match);
            }
        }
        matches.sort(Comparator
                .comparingInt(FileMatch::score).reversed()
                .thenComparing(m -> m.file.relativePath()));

        // 为每个命中文件构建合并后的片段。
        List<SearchResult.ContextFragment> allFragments = new ArrayList<>();
        for (FileMatch match : matches) {
            allFragments.addAll(buildFragments(match));
        }
        // 全局确定性排序：文件分数降序，路径升序，起始行升序。
        allFragments.sort(Comparator
                .comparingInt(SearchResult.ContextFragment::score).reversed()
                .thenComparing(SearchResult.ContextFragment::relativePath)
                .thenComparingInt(SearchResult.ContextFragment::fromLine));

        List<SearchResult.ContextFragment> packed = new ArrayList<>();
        int used = 0;
        boolean truncated = false;
        for (int i = 0; i < allFragments.size(); i++) {
            SearchResult.ContextFragment fragment = allFragments.get(i);
            int cost = CharBuf.codePointCount(fragment.text());
            int separatorCost = packed.isEmpty() ? 0
                    : CharBuf.codePointCount(properties.getFragmentSeparator());
            if (used + separatorCost + cost <= budgetChars) {
                used += separatorCost + cost;
                packed.add(fragment);
            } else {
                // 仅当后面确实还有未装下的片段时才构成"裁剪"；
                // 放不下的恰好是最后一个片段，则全部内容都已返回，不算裁剪。
                truncated = i < allFragments.size() - 1;
                log.info("budget cut at fragment #{}/{}: path={}, lines={}-{}, fragmentCost={}, used={}, budget={}, moreFragmentsAfterCut={}",
                        i + 1, allFragments.size(), fragment.relativePath(),
                        fragment.fromLine(), fragment.toLine(), cost, used, budgetChars, truncated);
                break;
            }
        }
        if (allFragments.isEmpty()) {
            truncated = false;
        }

        log.info("search: query='{}', terms={}, generation={}, hitFiles={}, fragments={}/{}, budget={}, used={}, truncated={}",
                query, terms, snapshot.generation(), matches.size(),
                packed.size(), allFragments.size(), budgetChars, used, truncated);

        return new SearchResult(
                query, budgetChars, snapshot.generation(),
                matches.size(), List.copyOf(packed), used,
                truncated, truncated ? TRUNCATION_RULE : "");
    }

    private FileMatch scoreFile(IndexedFile file, List<String> terms) {
        List<String> lines = file.lines();
        TreeMap<Integer, Integer> lineScores = new TreeMap<>();
        int total = 0;

        for (int i = 0; i < lines.size(); i++) {
            String lineLower = lines.get(i).toLowerCase(Locale.ROOT);
            List<String> lineTokens = TextTokenizer.tokenize(lineLower);
            Set<String> lineTokenSet = new LinkedHashSet<>(lineTokens);
            int lineScore = 0;
            for (String term : terms) {
                if (lineTokenSet.contains(term)) {
                    lineScore++;
                }
            }
            if (lineScore > 0) {
                lineScores.put(i + 1, lineScore); // 1-based 行号
                total += lineScore;
            }
        }

        String nameLower = file.relativePath().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            if (nameLower.contains(term)) {
                total += 2;
            }
        }

        // 行内无任何命中且文件名也不含任何 term 时，该文件不是命中。
        if (lineScores.isEmpty()) {
            boolean nameHit = false;
            for (String term : terms) {
                if (nameLower.contains(term)) {
                    nameHit = true;
                    break;
                }
            }
            if (!nameHit) {
                return null;
            }
        }
        return new FileMatch(file, total, lineScores);
    }

    /**
     * 将命中行按上下文半径扩展为窗口并合并重叠/相邻窗口。
     */
    private List<SearchResult.ContextFragment> buildFragments(FileMatch match) {
        List<String> lines = match.file.lines();
        int radius = Math.max(0, properties.getSnippetContextLines());

        // 合并窗口 [from,to]（0-based，含端点）。
        List<int[]> windows = new ArrayList<>();
        for (int lineNo : match.matchedLines.keySet()) {
            int center = lineNo - 1;
            int from = Math.max(0, center - radius);
            int to = Math.min(lines.size() - 1, center + radius);
            if (windows.isEmpty() || from > windows.get(windows.size() - 1)[1] + 1) {
                windows.add(new int[]{from, to});
            } else {
                int[] last = windows.get(windows.size() - 1);
                last[1] = Math.max(last[1], to);
            }
        }

        List<SearchResult.ContextFragment> fragments = new ArrayList<>();
        for (int[] window : windows) {
            StringBuilder sb = new StringBuilder();
            List<Integer> matchedInWindow = new ArrayList<>();
            for (int i = window[0]; i <= window[1]; i++) {
                if (i > window[0]) {
                    sb.append('\n');
                }
                sb.append(lines.get(i));
                int lineNo = i + 1;
                if (match.matchedLines.containsKey(lineNo)) {
                    matchedInWindow.add(lineNo);
                }
            }
            String header = match.file.relativePath() + ":" + (window[0] + 1)
                    + "-" + (window[1] + 1) + "\n";
            fragments.add(new SearchResult.ContextFragment(
                    match.file.relativePath(),
                    match.score(),
                    window[0] + 1,
                    window[1] + 1,
                    header + sb,
                    List.copyOf(matchedInWindow)));
        }
        return fragments;
    }

    private record FileMatch(IndexedFile file, int score, TreeMap<Integer, Integer> matchedLines) {
    }
}
