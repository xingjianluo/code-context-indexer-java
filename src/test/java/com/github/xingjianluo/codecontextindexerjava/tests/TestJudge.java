package com.github.xingjianluo.codecontextindexerjava.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试判定日志工具：统一打印受影响输入与判定依据，满足"单测日志中打印受影响输入与判定依据"。
 */
final class TestJudge {

    private static final Logger log = LoggerFactory.getLogger(TestJudge.class);

    private TestJudge() {
    }

    static void judge(String caseName, String affectedInput, String expected, String actual) {
        log.info("[CASE] {} | affectedInput={} | expected={} | actual={} | verdict={}",
                caseName, affectedInput, expected, actual,
                String.valueOf(expected).equals(String.valueOf(actual)) ? "MATCH" : "MISMATCH");
    }

    static void info(String caseName, String affectedInput, String basis, Object detail) {
        log.info("[CASE] {} | affectedInput={} | basis={} | detail={}",
                caseName, affectedInput, basis, detail);
    }
}
