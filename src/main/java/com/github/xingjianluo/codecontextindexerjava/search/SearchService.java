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
 * 评分规则（纯函数）：查询按非字母数字切词、转小写；
 * 片段得分 = 各词在片段内容（小写）中出现次数之和。
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
        if (hits.size() > properties.getMaxResults()) {
            hits = new ArrayList<>(hits.subList(0, properties.getMaxResults()));
        }
        return new SearchOutcome(snapshot.version(), List.copyOf(hits));
    }

    /** 检索结果及其所基于的快照版本。 */
    public record SearchOutcome(long snapshotVersion, List<SearchHit> hits) {
    }

    static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String[] parts = query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+");
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                tokens.add(p);
            }
        }
        return tokens;
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
