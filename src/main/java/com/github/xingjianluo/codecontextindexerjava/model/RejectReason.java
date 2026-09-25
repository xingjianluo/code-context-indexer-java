package com.github.xingjianluo.codecontextindexerjava.model;

/** 文件被拒绝索引的可区分原因。 */
public enum RejectReason {
    /** 文件不可读（权限或 IO 错误）。 */
    UNREADABLE,
    /** 扩展名不在支持列表中。 */
    UNSUPPORTED_TYPE,
    /** 文件超过大小上限。 */
    TOO_LARGE,
    /** 内容疑似二进制。 */
    BINARY,
    /** 不是常规文件（目录、符号链接环等）。 */
    NOT_REGULAR_FILE
}
