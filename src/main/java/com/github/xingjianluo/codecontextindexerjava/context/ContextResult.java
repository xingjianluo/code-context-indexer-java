package com.github.xingjianluo.codecontextindexerjava.context;

import com.github.xingjianluo.codecontextindexerjava.search.SearchHit;

import java.util.List;

/**
 * 上下文组装结果：预算内选中的片段、被裁剪的命中及裁剪依据。
 */
public record ContextResult(
        String query,
        int budgetChars,
        int usedChars,
        List<SearchHit> included,
        List<TrimmedHit> trimmed) {

    /** 因预算不足被裁剪的命中及依据。 */
    public record TrimmedHit(SearchHit hit, String reason) {
    }
}
