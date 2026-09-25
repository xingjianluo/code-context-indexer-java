package com.github.xingjianluo.codecontextindexerjava.model;

/**
 * 文件被拒绝索引的可区分原因。
 */
public enum RejectReason {
    /** 扩展名不在允许列表内（疑似二进制或不受支持的文本类型）。 */
    UNSUPPORTED_EXTENSION,
    /** 文件大小超过配置的上限。 */
    TOO_LARGE,
    /** 文件存在但当前进程无权读取。 */
    UNREADABLE,
    /** 读取过程中发生 I/O 或安全异常。 */
    READ_ERROR,
    /** 非常规文件（符号链接、管道、设备文件等）。 */
    NOT_REGULAR_FILE
}
