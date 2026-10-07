package com.github.xingjianluo.codecontextindexerjava.index;

/**
 * 刷新索引时 rootDir 参数非法。携带可区分的原因，
 * 抛出时已发布快照不受影响，检索结果保持可用。
 */
public class InvalidRootDirException extends RuntimeException {

    /** 非法原因，彼此可区分。 */
    public enum Reason {
        /** 路径不存在。 */
        NOT_FOUND,
        /** 路径存在但不是目录。 */
        NOT_A_DIRECTORY,
        /** 是目录但不可读。 */
        UNREADABLE
    }

    private final Reason reason;
    private final String rootDir;

    public InvalidRootDirException(Reason reason, String rootDir, String message) {
        super(message);
        this.reason = reason;
        this.rootDir = rootDir;
    }

    public Reason reason() {
        return reason;
    }

    public String rootDir() {
        return rootDir;
    }
}
