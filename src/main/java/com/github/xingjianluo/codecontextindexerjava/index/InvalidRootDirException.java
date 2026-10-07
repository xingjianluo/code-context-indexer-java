package com.github.xingjianluo.codecontextindexerjava.index;

import java.nio.file.Path;

/**
 * 刷新请求的 rootDir 参数非法。携带可区分的原因，
 * 由接口层映射为 400 响应；抛出时旧快照不受影响。
 */
public class InvalidRootDirException extends RuntimeException {

    /** 可区分的非法原因。 */
    public enum Reason {
        /** 路径不存在。 */
        NOT_EXISTS,
        /** 路径存在但不是目录。 */
        NOT_A_DIRECTORY,
        /** 是目录但不可读。 */
        UNREADABLE
    }

    private final Reason reason;
    private final String rootDir;

    public InvalidRootDirException(Reason reason, Path rootDir) {
        super(messageOf(reason, rootDir));
        this.reason = reason;
        this.rootDir = rootDir.toString();
    }

    public Reason reason() {
        return reason;
    }

    public String rootDir() {
        return rootDir;
    }

    private static String messageOf(Reason reason, Path rootDir) {
        return switch (reason) {
            case NOT_EXISTS -> "索引根目录不存在: " + rootDir;
            case NOT_A_DIRECTORY -> "索引根路径不是目录: " + rootDir;
            case UNREADABLE -> "索引根目录不可读: " + rootDir;
        };
    }
}
