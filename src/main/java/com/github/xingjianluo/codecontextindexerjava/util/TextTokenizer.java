package com.github.xingjianluo.codecontextindexerjava.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 确定性分词：以非字母/非数字字符为分隔符，按 {@link Locale#ROOT} 小写化。
 * 不依赖任何外部库，相同输入永远得到相同 token 序列（顺序、数量都稳定）。
 */
public final class TextTokenizer {

    private TextTokenizer() {
    }

    /**
     * @param text 原始文本，可为 null
     * @return 不可变的 token 列表；无 token 时为空列表
     */
    public static List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        // Unicode 字母/数字以外的一切字符（下划线、空白、标点、CJK 标点等）均作为分隔符。
        String[] parts = lower.split("[^\\p{L}\\p{N}]+");
        List<String> tokens = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                tokens.add(part);
            }
        }
        return List.copyOf(tokens);
    }
}
