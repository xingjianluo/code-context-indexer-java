package com.github.xingjianluo.codecontextindexerjava.scanner;

import com.github.xingjianluo.codecontextindexerjava.model.IndexedFile;
import com.github.xingjianluo.codecontextindexerjava.model.RejectedFile;

import java.util.List;
import java.util.Map;

/**
 * 一次目录扫描的完整结果：按相对路径索引的全部接受文件 + 全部拒绝记录。
 */
public record ScanOutcome(
        Map<String, IndexedFile> accepted,
        List<RejectedFile> rejected
) {
}
