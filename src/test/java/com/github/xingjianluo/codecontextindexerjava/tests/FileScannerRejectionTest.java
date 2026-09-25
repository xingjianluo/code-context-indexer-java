package com.github.xingjianluo.codecontextindexerjava.tests;

import com.github.xingjianluo.codecontextindexerjava.index.Chunker;
import com.github.xingjianluo.codecontextindexerjava.index.FileScanner;
import com.github.xingjianluo.codecontextindexerjava.index.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.model.RejectReason;
import com.github.xingjianluo.codecontextindexerjava.model.RejectedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不可读/不受支持的文件不得静默跳过，拒绝原因必须可区分。 */
class FileScannerRejectionTest {

    private static final Logger log = LoggerFactory.getLogger(FileScannerRejectionTest.class);

    @TempDir
    Path root;

    @Test
    void rejectionReasonsAreDistinguishable() throws Exception {
        IndexerProperties props = new IndexerProperties();
        props.setMaxFileSizeBytes(64);
        FileScanner scanner = new FileScanner(props, new Chunker(props));

        Files.writeString(root.resolve("ok.java"), "class Ok {}");
        Files.writeString(root.resolve("note.exe"), "plain text but unsupported extension");
        Files.writeString(root.resolve("big.java"), "x".repeat(128));
        Files.write(root.resolve("raw.java"), new byte[]{0x00, 0x01, 0x02}); // 含 NUL，判二进制

        FileScanner.ScanResult result = scanner.scan(root);
        Map<String, RejectedFile> byPath = result.rejected().stream()
                .collect(Collectors.toMap(RejectedFile::path, Function.identity()));

        log.info("[拒绝分类] 受影响输入=note.exe,big.java,raw.java; 判定依据: "
                        + "note.exe={}, big.java={}, raw.java={}",
                byPath.get("note.exe").reason(), byPath.get("big.java").reason(),
                byPath.get("raw.java").reason());

        assertEquals(1, result.indexed().size(), "仅 ok.java 应被索引");
        assertEquals("ok.java", result.indexed().get(0).path());
        assertEquals(3, result.rejected().size(), "三个问题文件均不得被静默跳过");
        assertEquals(RejectReason.UNSUPPORTED_TYPE, byPath.get("note.exe").reason());
        assertEquals(RejectReason.TOO_LARGE, byPath.get("big.java").reason());
        assertEquals(RejectReason.BINARY, byPath.get("raw.java").reason());
        // 每个拒绝都带可解释明细
        result.rejected().forEach(r -> {
            log.info("[拒绝明细] path={} reason={} detail={}", r.path(), r.reason(), r.detail());
            assertTrue(r.detail() != null && !r.detail().isBlank());
        });
    }
}
