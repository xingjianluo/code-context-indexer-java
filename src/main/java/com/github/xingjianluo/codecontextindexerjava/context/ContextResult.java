package com.github.xingjianluo.codecontextindexerjava.context;

import com.github.xingjianluo.codecontextindexerjava.search.SearchHit;

import java.util.List;

/**
 * 上下文组装结果：预算内选中的片段、被裁剪的命中及裁剪依据，
 * 以及检索阶段的命中总数与截断说明。
 *
 * @param totalHits          本次查询实际命中的片段总数（截断前）
 * @param hitLimit           单次返回上限（indexer.max-results）
 * @param truncatedByHitLimit 是否因命中数超过上限被截断
 * @param retentionRule      截断时保留/丢弃所依据的规则说明
 */
public record ContextResult(
        String query,
        int budgetChars,
        int usedChars,
        int totalHits,
        int hitLimit,
        boolean truncatedByHitLimit,
        String retentionRule,
        List<SearchHit> included,
        List<TrimmedHit> trimmed) {

    /** 因预算不足被裁剪的命中及依据。 */
    public record TrimmedHit(SearchHit hit, String reason) {
    }
}
