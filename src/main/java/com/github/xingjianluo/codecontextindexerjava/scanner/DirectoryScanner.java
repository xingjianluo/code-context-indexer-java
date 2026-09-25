package com.github.xingjianluo.codecontextindexerjava.scanner;

import com.github.xingjianluo.codecontextindexerjava.config.IndexerProperties;
import com.github.xingjianluo.codecontextindexerjava.model.IndexedFile;
import com.github.xingjianluo.codecontextindexerjava.model.RejectReason;
import com.github.xingjianluo.codecontextindexerjava.model.RejectedFile;
import com.github.xingjianluo.codecontextindexerjava.util.CharBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 遍历代码根目录，读取并规范化每个受支持的文本文件，产出扫描结果与拒绝记录。
 *
 * <p>拒绝策略（任何拒绝都不会静默跳过，全部进入 rejected 列表，原因可区分）：</p>
 * <ol>
 *   <li>符号链接、管道、设备文件等非常规文件 → {@link RejectReason#NOT_REGULAR_FILE}；</li>
 *   <li>扩展名不在 cci.supported-extensions 内 → {@link RejectReason#UNSUPPORTED_EXTENSION}；</li>
 *   <li>大小超过 cci.max-file-bytes → {@link RejectReason#TOO_LARGE}；</li>
 *   <li>无读权限 → {@link RejectReason#UNREADABLE}；</li>
 *   <li>读取时 I/O / 安全异常 → {@link RejectReason#READ_ERROR}。</li>
 * </ol>
 *
 * <p>排除目录（cci.exclude-dirs）属于<b>配置策略</b>而非文件错误，
 * 直接跳过且不产生拒绝记录，并在日志中说明。</p>
 */
@Component
public class DirectoryScanner {

    private static final Logger log = LoggerFactory.getLogger(DirectoryScanner.class);

    private final IndexerProperties properties;

    public DirectoryScanner(IndexerProperties properties) {
        this.properties = properties;
    }

    /**
     * 对根目录执行一次完整扫描。
     *
     * @param root 已由调用方规范化、确认存在的目录
     */
    public ScanOutcome scan(Path root) throws IOException {
        if (root == null) {
            throw new IllegalArgumentException("scan root must not be null");
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("index root is not a readable directory: " + root);
        }

        TreeMap<String, IndexedFile> accepted = new TreeMap<>();
        List<RejectedFile> rejected = new ArrayList<>();
        List<String> excludedDirs = new ArrayList<>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                if (properties.getExcludeDirs().contains(name)) {
                    excludedDirs.add(relative(root, dir));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // 显式拒绝指向目录的符号链接，防止链接环与目录穿越。
                if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)) {
                    rejected.add(new RejectedFile(
                            relative(root, dir), dir.toAbsolutePath().toString(),
                            RejectReason.NOT_REGULAR_FILE, "symbolic link to directory"));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = relative(root, file);
                String abs = file.toAbsolutePath().toString();

                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    reject(rejected, rel, abs, RejectReason.NOT_REGULAR_FILE,
                            "not a regular file (symlink, pipe, device or other special file)");
                    return FileVisitResult.CONTINUE;
                }

                String ext = extension(file);
                if (!properties.getSupportedExtensions().contains(ext)) {
                    reject(rejected, rel, abs, RejectReason.UNSUPPORTED_EXTENSION,
                            "extension '." + ext + "' is not in supported list "
                                    + properties.sortedSupportedExtensions());
                    return FileVisitResult.CONTINUE;
                }

                long size = attrs.size();
                if (size > properties.getMaxFileBytes()) {
                    reject(rejected, rel, abs, RejectReason.TOO_LARGE,
                            "size " + size + " bytes exceeds limit " + properties.getMaxFileBytes() + " bytes");
                    return FileVisitResult.CONTINUE;
                }

                if (!Files.isReadable(file)) {
                    reject(rejected, rel, abs, RejectReason.UNREADABLE,
                            "process has no read permission for this file");
                    return FileVisitResult.CONTINUE;
                }

                try {
                    String raw = CharBuf.readUtf8(file);
                    List<String> lines = normalizeLines(raw);
                    String hash = sha256(String.join("\n", lines));
                    accepted.put(rel, new IndexedFile(
                            rel, abs, size, attrs.lastModifiedTime().toMillis(), hash, lines));
                } catch (SecurityException se) {
                    reject(rejected, rel, abs, RejectReason.UNREADABLE,
                            "security manager denied read: " + se.getMessage());
                } catch (IOException ioe) {
                    reject(rejected, rel, abs, RejectReason.READ_ERROR,
                            ioe.getClass().getSimpleName() + ": " + ioe.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                String rel = relative(root, file);
                String abs = file.toAbsolutePath().toString();
                RejectReason reason = Files.isReadable(file)
                        ? RejectReason.READ_ERROR : RejectReason.UNREADABLE;
                reject(rejected, rel, abs, reason,
                        exc.getClass().getSimpleName() + ": " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });

        // 拒绝记录按相对路径、再按原因排序，保证输出确定、可复现。
        rejected.sort((a, b) -> {
            int byPath = a.relativePath().compareTo(b.relativePath());
            return byPath != 0 ? byPath : a.reason().compareTo(b.reason());
        });
        excludedDirs.sort(String::compareTo);

        log.info("scan finished: root={}, accepted={}, rejected={} (reasons={}), excludedDirs={}",
                root, accepted.size(), rejected.size(), reasonCounts(rejected), excludedDirs);
        rejected.forEach(r -> log.info("rejected input: path={}, reason={}, detail={}",
                r.relativePath(), r.reason(), r.detail()));

        Map<String, IndexedFile> immutableAccepted = Map.copyOf(accepted);
        return new ScanOutcome(immutableAccepted, List.copyOf(rejected));
    }

    private static void reject(List<RejectedFile> rejected, String rel, String abs,
                               RejectReason reason, String detail) {
        log.warn("reject file: path={}, reason={}, detail={}", rel, reason, detail);
        rejected.add(new RejectedFile(rel, abs, reason, detail));
    }

    /**
     * 规范化文本行：统一 CRLF/CR 为 LF 后切分，并去掉文件末尾空行。
     * 同一份内容无论原始行尾风格如何，都得到完全相同的行列表与哈希。
     */
    static List<String> normalizeLines(String raw) {
        String lfOnly = raw.replace("\r\n", "\n").replace('\r', '\n');
        String trimmed = lfOnly;
        while (!trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == '\n') {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return List.of();
        }
        return List.of(trimmed.split("\n", -1));
    }

    private static String sha256(String normalizedContent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String relative(Path root, Path path) {
        String rel = root.relativize(path).toString().replace('\\', '/');
        return rel;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static Map<RejectReason, Long> reasonCounts(List<RejectedFile> rejected) {
        Map<RejectReason, Long> counts = new TreeMap<>();
        for (RejectedFile r : rejected) {
            counts.merge(r.reason(), 1L, Long::sum);
        }
        return counts;
    }
}
