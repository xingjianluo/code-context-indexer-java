package com.github.xingjianluo.codecontextindexerjava.model;

import java.util.List;

/**
 * 一次上下文检索的可复现结果。
 */
public record SearchResult(
        String query,
        int budgetChars,
        long generation,
        int totalHits,
        List<ContextFragment> fragments,
        int returnedChars,
        boolean truncated,
        String truncationRule
) {
    /**
     * 预算内返回的上下文片段。
     */
    public record ContextFragment(
            String relativePath,
            int score,
            int fromLine,
            int toLine,
            String text,
            List<Integer> matchedLines
    ) {
    }
}
