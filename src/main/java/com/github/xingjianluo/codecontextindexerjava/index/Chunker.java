package com.github.xingjianluo.codecontextindexerjava.index;

import com.github.xingjianluo.codecontextindexerjava.model.IndexedChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将文件内容按确定规则切分为片段：
 * 按行累积，达到 chunkMaxLines 行或 chunkMaxChars 字符即切分；
 * 超长单行按 chunkMaxChars 硬切。相同输入必产生相同片段序列。
 */
@Component
public class Chunker {

    private final IndexerProperties properties;

    public Chunker(IndexerProperties properties) {
        this.properties = properties;
    }

    public List<IndexedChunk> chunk(String path, String content) {
        List<IndexedChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        StringBuilder current = new StringBuilder();
        int startLine = 1;
        int lineNo = 1;
        int chunkIndex = 0;
        for (String line : lines) {
            // 超长行硬切：先结算当前片段，再按 chunkMaxChars 切分该行
            if (line.length() > properties.getChunkMaxChars()) {
                if (current.length() > 0) {
                    chunks.add(new IndexedChunk(path, chunkIndex++, startLine, lineNo - 1, current.toString()));
                    current.setLength(0);
                }
                int from = 0;
                while (from < line.length()) {
                    int to = Math.min(from + properties.getChunkMaxChars(), line.length());
                    chunks.add(new IndexedChunk(path, chunkIndex++, lineNo, lineNo, line.substring(from, to)));
                    from = to;
                }
                startLine = lineNo + 1;
                lineNo++;
                continue;
            }
            boolean wouldExceedChars = current.length() > 0
                    && current.length() + 1 + line.length() > properties.getChunkMaxChars();
            boolean wouldExceedLines = lineNo - startLine + 1 > properties.getChunkMaxLines();
            if (wouldExceedChars || wouldExceedLines) {
                chunks.add(new IndexedChunk(path, chunkIndex++, startLine, lineNo - 1, current.toString()));
                current.setLength(0);
                startLine = lineNo;
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(line);
            lineNo++;
        }
        if (current.length() > 0) {
            chunks.add(new IndexedChunk(path, chunkIndex, startLine, lineNo - 1, current.toString()));
        }
        return chunks;
    }
}
