package com.github.xingjianluo.codecontextindexerjava.context;

import com.github.xingjianluo.codecontextindexerjava.search.SearchHit;
import com.github.xingjianluo.codecontextindexerjava.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 在给定字符预算内组装上下文片段。
 *
 * 裁剪规则（确定性）：按检索排名顺序贪心装入，
 * 片段可完整放入剩余预算则纳入，否则记入 trimmed 并附原因
 * （需要字符数 vs 剩余预算）。相同命中序列 + 相同预算
 * 必得到相同的 included/trimmed 结论。
 */
@Component
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    /** 片段间分隔符，计入预算。 */
    static final String SEPARATOR = "\n---\n";

    /** 命中数超限时的截断规则说明（固定文案，便于调用方与测试核对）。 */
    static final String HIT_LIMIT_RULE =
            "命中数超过上限时按排名（得分降序，并列按 path/chunkIndex 字典序）"
                    + "保留前 hitLimit 条，丢弃排名靠后的其余命中";

    public ContextResult assemble(String query, SearchService.SearchOutcome outcome, int budgetChars) {
        if (budgetChars < 0) {
            throw new IllegalArgumentException("预算不能为负: " + budgetChars);
        }
        List<SearchHit> included = new ArrayList<>();
        List<ContextResult.TrimmedHit> trimmed = new ArrayList<>();
        int used = 0;
        for (SearchHit hit : outcome.hits()) {
            int cost = hit.chunk().content().length()
                    + (included.isEmpty() ? 0 : SEPARATOR.length());
            if (used + cost <= budgetChars) {
                included.add(hit);
                used += cost;
            } else {
                String reason = "预算不足：片段需 " + cost + " 字符，剩余 "
                        + (budgetChars - used) + " 字符";
                trimmed.add(new ContextResult.TrimmedHit(hit, reason));
                log.debug("裁剪命中 path={} chunkIndex={} 依据={}",
                        hit.chunk().path(), hit.chunk().chunkIndex(), reason);
            }
        }
        return new ContextResult(query, budgetChars, used,
                outcome.totalHits(), outcome.returnedHits(), outcome.hitLimit(),
                outcome.truncated(), HIT_LIMIT_RULE,
                List.copyOf(included), List.copyOf(trimmed));
    }
}
