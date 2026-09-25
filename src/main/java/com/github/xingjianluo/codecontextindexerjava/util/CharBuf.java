package com.github.xingjianluo.codecontextindexerjava.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 以 UTF-8 读取文本文件，并按码点统计字符数，避免多字节字符导致预算计算漂移。
 */
public final class CharBuf {

    private CharBuf() {
    }

    public static String readUtf8(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static int codePointCount(String text) {
        return text.codePointCount(0, text.length());
    }
}
