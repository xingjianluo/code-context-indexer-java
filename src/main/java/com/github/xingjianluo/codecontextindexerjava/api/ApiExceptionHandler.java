package com.github.xingjianluo.codecontextindexerjava.api;

import com.github.xingjianluo.codecontextindexerjava.index.InvalidRootDirException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 接口层异常映射：非法的刷新目录参数返回 400，
 * 响应体携带可区分的 error 码（NOT_EXISTS / NOT_A_DIRECTORY / UNREADABLE）、
 * 人类可读说明与出错的 rootDir；旧快照不受失败请求影响。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRootDirException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRootDir(InvalidRootDirException e) {
        log.warn("刷新请求被拒绝 reason={} rootDir={}", e.reason(), e.rootDir());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", e.reason().name(),
                "message", e.getMessage(),
                "rootDir", e.rootDir()));
    }
}
