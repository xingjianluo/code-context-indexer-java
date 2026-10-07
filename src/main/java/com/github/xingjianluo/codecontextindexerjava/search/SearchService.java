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
 * 分词规则（纯函数，对中文/英文/中英混排统一处理）：
 * - 连续的拉丁字母/数字/下划线切为一个词，转小写；
 * - 连续的 CJK 字符切为一个中文段：长度为 1 时以单字为词，
 *   长度 ≥ 2 时拆为相邻二字组（bigram），逐字滑窗；
 * - 其余字符（空白、标点等）仅作分隔。
 *
 * 评分规则：片段得分 = 各词在片段内容（小写）中出现次数之和；
 * 中文二字组按子串匹配，因此中文、英文及中英混排查询都能命中
 * 确实包含对应内容的片段。
 *
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

    /** 在当前已发布快照上检索，返回有序命中列表（含快照版本号便于校验一致性）。 */
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
        int limit = properties.getMaxResults();
        boolean truncated = totalHits > limit;
        if (truncated) {
            hits = new ArrayList<>(hits.subList(0, limit));
        }
        return new SearchOutcome(snapshot.version(), totalHits, limit, truncated,
                List.copyOf(hits));
    }

    /**
     * 检索结果及其所基于的快照版本。
     *
     * @param snapshotVersion 结果所基于的快照版本
     * @param totalHits       本次查询实际命中的片段总数（截断前）
     * @param hitLimit        单次返回的命中数上限（indexer.max-results）
     * @param truncated       是否因命中数超过上限而发生截断；
     *                        截断时按既定排名（得分降序，并列按 path/chunkIndex 字典序）
     *                        保留前 hitLimit 条，丢弃其余
     * @param hits            本次实际返回的命中（已按排名截断）
     */
    public record SearchOutcome(long snapshotVersion, int totalHits, int hitLimit,
                                boolean truncated, List<SearchHit> hits) {
        /** 本次实际返回的命中条数。 */
        public int returnedHits() {
            return hits.size();
        }
    }

    static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (isCjk(c)) {
                flushLatin(latin, tokens);
                cjk.append(c);
            } else if (isLatinTokenChar(c)) {
                flushCjk(cjk, tokens);
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    /** 拉丁字母/数字/下划线（ASCII）。 */
    private static boolean isLatinTokenChar(char c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '_');
    }

    /** CJK 统一表意文字及扩展区、兼容表意文字。 */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    /** 中文段切词：单字直接成词，多字拆为相邻二字组。 */
    private static void flushCjk(StringBuilder cjk, List<String> tokens) {
        int len = cjk.length();
        if (len == 1) {
            tokens.add(cjk.toString());
        } else if (len > 1) {
            for (int i = 0; i + 1 < len; i++) {
                tokens.add(cjk.substring(i, i + 2));
            }
        }
        cjk.setLength(0);
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
