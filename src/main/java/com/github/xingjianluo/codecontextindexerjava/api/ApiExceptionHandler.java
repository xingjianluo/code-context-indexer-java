package com.github.xingjianluo.codecontextindexerjava.api;

import com.github.xingjianluo.codecontextindexerjava.index.InvalidRootDirException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 接口层异常映射：把可预期的失败转为状态码与原因都可区分的响应体。
 * 刷新失败时已发布快照不受影响，检索接口继续可用。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** rootDir 参数非法 → 400，reason 区分 不存在/不是目录/不可读。 */
    @ExceptionHandler(InvalidRootDirException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRootDir(InvalidRootDirException e) {
        log.warn("刷新参数非法 reason={} rootDir={} 消息={}", e.reason(), e.rootDir(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INVALID_ROOT_DIR");
        body.put("reason", e.reason().name());
        body.put("rootDir", e.rootDir());
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 扫描过程 IO 失败 → 500，但消息可读；已发布快照保持不变。 */
    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, Object>> handleScanIo(UncheckedIOException e) {
        log.warn("刷新扫描失败 消息={}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "SCAN_FAILED");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
