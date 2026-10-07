package com.github.xingjianluo.codecontextindexerjava.tests;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层串联验证：刷新 → 状态 → 中文检索（含截断说明）
 * → 失败刷新（可区分 400）→ 旧结果仍可用 → 恢复刷新。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ApiIntegrationTest.class);

    @TempDir
    static Path sharedRoot;

    @Autowired
    MockMvc mvc;

    @Test
    @Order(1)
    void refreshStatusAndChineseSearchFlow() throws Exception {
        Files.createDirectories(sharedRoot);
        for (int i = 0; i < 5; i++) {
            Files.writeString(sharedRoot.resolve("文档" + i + ".md"),
                    "# 用户手册 " + i + "\n本文介绍索引服务的部署与检索用法。");
        }

        MvcResult refresh = mvc.perform(post("/api/index/refresh")
                        .param("rootDir", sharedRoot.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedTotal").value(5))
                .andReturn();
        log.info("刷新响应={}", refresh.getResponse().getContentAsString());

        MvcResult status = mvc.perform(get("/api/index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedCount").value(5))
                .andReturn();
        log.info("状态响应={}", status.getResponse().getContentAsString());

        // indexer.max-results 默认 50，此处命中 5 条不截断；
        // 截断字段必须存在且语义正确
        MvcResult search = mvc.perform(get("/api/search")
                        .param("q", "部署")
                        .param("budget", "4000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(5))
                .andExpect(jsonPath("$.truncatedByHitLimit").value(false))
                .andExpect(jsonPath("$.retentionRule").isString())
                .andReturn();
        String body = search.getResponse().getContentAsString();
        log.info("中文检索响应={}", body);
        assertTrue(body.contains("部署"), "响应应包含命中片段中的中文内容");
    }

    @Test
    @Order(2)
    void failedRefreshKeepsOldResultsUsable() throws Exception {
        MvcResult badRequest = mvc.perform(post("/api/index/refresh")
                        .param("rootDir", sharedRoot.resolve("不存在").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_ROOT_DIR"))
                .andExpect(jsonPath("$.reason").value("NOT_FOUND"))
                .andReturn();
        log.info("非法目录响应={}", badRequest.getResponse().getContentAsString());

        MvcResult notDir = mvc.perform(post("/api/index/refresh")
                        .param("rootDir", sharedRoot.resolve("文档0.md").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("NOT_A_DIRECTORY"))
                .andReturn();
        log.info("文件当目录响应={}", notDir.getResponse().getContentAsString());

        // 失败刷新后旧索引仍可检索
        mvc.perform(get("/api/search").param("q", "部署").param("budget", "4000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(5));
        log.info("失败刷新后旧检索结果仍可用 totalHits=5");

        // 换回正确目录可恢复
        mvc.perform(post("/api/index/refresh").param("rootDir", sharedRoot.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedTotal").value(5));
        log.info("恢复刷新成功");
    }
}
