package com.github.xingjianluo.codecontextindexerjava.index;

import com.github.xingjianluo.codecontextindexerjava.model.IndexedFile;
import com.github.xingjianluo.codecontextindexerjava.model.RejectReason;
import com.github.xingjianluo.codecontextindexerjava.model.RejectedFile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 扫描目录：对每个文件给出确定结论 —— 成功索引或带可区分原因的拒绝。
 * 遍历按相对路径字典序排序，保证扫描顺序可复现。
 */
@Component
public class FileScanner {

    /** 扫描结果：已索引文件与被拒绝文件均按路径排序。 */
    public record ScanResult(List<IndexedFile> indexed, List<RejectedFile> rejected) {
    }

    private final IndexerProperties properties;
    private final Chunker chunker;

    public FileScanner(IndexerProperties properties, Chunker chunker) {
        this.properties = properties;
        this.chunker = chunker;
    }

    public ScanResult scan(Path root) {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.filter(p -> !p.equals(root)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("扫描目录失败: " + root, e);
        }
        List<IndexedFile> indexed = new ArrayList<>();
        List<RejectedFile> rejected = new ArrayList<>();
        for (Path p : paths) {
            String rel = root.relativize(p).toString();
            if (Files.isDirectory(p)) {
                continue; // 目录本身不入索引，也不计为拒绝
            }
            if (!Files.isRegularFile(p)) {
                rejected.add(new RejectedFile(rel, RejectReason.NOT_REGULAR_FILE, "非常规文件"));
                continue;
            }
            if (!Files.isReadable(p)) {
                rejected.add(new RejectedFile(rel, RejectReason.UNREADABLE, "文件不可读"));
                continue;
            }
            String ext = extensionOf(p.getFileName().toString());
            if (!properties.getSupportedExtensions().contains(ext)) {
                rejected.add(new RejectedFile(rel, RejectReason.UNSUPPORTED_TYPE, "不支持的扩展名: " + ext));
                continue;
            }
            long size;
            try {
                size = Files.size(p);
            } catch (IOException e) {
                rejected.add(new RejectedFile(rel, RejectReason.UNREADABLE, "无法读取元信息: " + e.getMessage()));
                continue;
            }
            if (size > properties.getMaxFileSizeBytes()) {
                rejected.add(new RejectedFile(rel, RejectReason.TOO_LARGE,
                        "大小 " + size + " 超过上限 " + properties.getMaxFileSizeBytes()));
                continue;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(p);
            } catch (IOException e) {
                rejected.add(new RejectedFile(rel, RejectReason.UNREADABLE, "读取失败: " + e.getMessage()));
                continue;
            }
            if (looksBinary(bytes)) {
                rejected.add(new RejectedFile(rel, RejectReason.BINARY, "内容疑似二进制"));
                continue;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            indexed.add(new IndexedFile(rel, size, sha256(bytes), chunker.chunk(rel, content)));
        }
        indexed.sort(Comparator.comparing(IndexedFile::path));
        rejected.sort(Comparator.comparing(RejectedFile::path));
        return new ScanResult(indexed, rejected);
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 含 NUL 字节或存在非法 UTF-8 序列即判定为二进制。 */
    private static boolean looksBinary(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return hasInvalidUtf8(bytes);
    }

    private static boolean hasInvalidUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes));
            return false;
        } catch (java.nio.charset.CharacterCodingException e) {
            return true;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
