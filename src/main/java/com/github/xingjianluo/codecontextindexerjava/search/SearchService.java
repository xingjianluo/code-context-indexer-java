package com.github.xingjianluo.codecontextindexerjava.search;

import com.github.xingjianluo.codecontextindexerjava.index.IndexService;
import com.github.xingjianluo.codecontextindexerjava.index.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.model.IndexSnapshot;
import com.github.xingjianluo.codecontextindexerjava.model.IndexedChunk;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 基于当前已发布快照的确定性检索。
 *
 * 评分规则（纯函数）：查询先转小写再切词 —— 连续的
 * 英文字母/数字/下划线构成一个词元；连续的 CJK 表意文字
 * 构成一个中文串，拆为全部单字词元与相邻二字词元
 * （单字保证单字查询可命中，二字组保证多字词的精确度）。
 * 片段得分 = 各词元在片段内容（小写）中出现次数之和。
 * 排序规则：得分降序，并列时按 (path, chunkIndex) 字典序，
 * 因此相同快照 + 相同查询必得到相同结果序列。
 */
@Service
public class SearchService {

    private final IndexService indexService;
    private final IndexerProperties properties;

    public SearchService(IndexService indexService, IndexerProperties properties) {
        this.indexService = indexService;
        this.properties = properties;
    }

    /** 在当前已发布快照上检索，返回有序命中列表与截断说明（含快照版本号便于校验一致性）。 */
    public SearchOutcome search(String query) {
        IndexSnapshot snapshot = indexService.currentSnapshot();
        List<String> tokens = tokenize(query);
        List<SearchHit> hits = new ArrayList<>();
        if (!tokens.isEmpty()) {
            for (IndexedChunk chunk : snapshot.allChunks()) {
                double score = score(chunk.content(), tokens);
                if (score > 0) {
                    hits.add(new SearchHit(chunk, score));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(SearchHit::score).reversed()
                .thenComparing(h -> h.chunk().path())
                .thenComparingInt(h -> h.chunk().chunkIndex()));
        int totalHits = hits.size();
        int maxResults = properties.getMaxResults();
        boolean truncated = totalHits > maxResults;
        // 截断规则：命中按排名（得分降序，并列按 path/chunkIndex 字典序）保留前
        // maxResults 条，丢弃排名靠后的部分；排名为纯函数，重复查询结论一致。
        List<SearchHit> returned = truncated
                ? List.copyOf(hits.subList(0, maxResults))
                : List.copyOf(hits);
        return new SearchOutcome(snapshot.version(), totalHits, maxResults, truncated, returned);
    }

    /**
     * 检索结果及其所基于的快照版本。
     *
     * @param snapshotVersion 检索基于的快照版本
     * @param totalHits       本次查询实际命中的片段总数（截断前）
     * @param maxResults      单次返回上限（配置 indexer.max-results）
     * @param truncated       是否因超过上限而发生截断
     * @param hits            本次实际返回的命中（截断时为排名前 maxResults 条）
     */
    public record SearchOutcome(long snapshotVersion, int totalHits, int maxResults,
                                boolean truncated, List<SearchHit> hits) {
        /** 本次实际返回的命中条数。 */
        public int returnedHits() {
            return hits.size();
        }
    }

    /**
     * 切词（确定性纯函数）：
     * <ul>
     *   <li>英文/数字/下划线的连续段 → 一个词元（小写）；</li>
     *   <li>CJK 表意文字连续段 → 全部单字词元 + 全部相邻二字词元；</li>
     *   <li>其余字符视为分隔符。</li>
     * </ul>
     * 中文、英文、中英混排查询都能得到非空词元序列。
     */
    static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String lower = query.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        int[] codePoints = lower.codePoints().toArray();
        for (int cp : codePoints) {
            if (isCjk(cp)) {
                flushLatin(latin, tokens);
                cjk.appendCodePoint(cp);
            } else if (isLatinTokenChar(cp)) {
                flushCjk(cjk, tokens);
                latin.appendCodePoint(cp);
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    /** 中文串拆为单字词元与相邻二字词元，保证单字查询与多字查询都能命中。 */
    private static void flushCjk(StringBuilder cjk, List<String> tokens) {
        if (cjk.length() == 0) {
            return;
        }
        int[] cps = cjk.toString().codePoints().toArray();
        for (int cp : cps) {
            tokens.add(new String(Character.toChars(cp)));
        }
        for (int i = 0; i + 1 < cps.length; i++) {
            tokens.add(new String(Character.toChars(cps[i])) + new String(Character.toChars(cps[i + 1])));
        }
        cjk.setLength(0);
    }

    private static boolean isLatinTokenChar(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9') || cp == '_';
    }

    /** CJK 统一表意文字（含扩展 A 区与兼容表意文字）。 */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0xF900 && cp <= 0xFAFF);
    }

    static double score(String content, List<String> tokens) {
        String lower = content.toLowerCase(Locale.ROOT);
        double total = 0;
        for (String token : tokens) {
            total += countOccurrences(lower, token);
        }
        return total;
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(token, idx)) >= 0) {
            count++;
            idx += token.length();
        }
        return count;
    }
}
