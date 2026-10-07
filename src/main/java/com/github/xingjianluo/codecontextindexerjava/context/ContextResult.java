package com.github.xingjianluo.codecontextindexerjava.context;

import com.github.xingjianluo.codecontextindexerjava.search.SearchHit;

import java.util.List;

/**
 * 上下文组装结果：预算内选中的片段、被裁剪的命中及裁剪依据，
 * 以及检索侧的命中总数与截断说明。
 *
 * @param totalHits           本次查询实际命中的片段总数（截断前）
 * @param returnedHits        检索实际返回的命中条数（= included + trimmed）
 * @param hitLimit            单次返回命中数上限（indexer.max-results）
 * @param truncatedByHitLimit 是否因命中数超过上限被截断
 * @param hitLimitRule        截断时保留/丢弃的规则说明
 */
public record ContextResult(
        String query,
        int budgetChars,
        int usedChars,
        int totalHits,
        int returnedHits,
        int hitLimit,
        boolean truncatedByHitLimit,
        String hitLimitRule,
        List<SearchHit> included,
        List<TrimmedHit> trimmed) {

    /** 因预算不足被裁剪的命中及依据。 */
    public record TrimmedHit(SearchHit hit, String reason) {
    }
}
