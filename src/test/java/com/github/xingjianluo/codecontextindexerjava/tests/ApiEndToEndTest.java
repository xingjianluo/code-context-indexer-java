package com.github.xingjianluo.codecontextindexerjava.tests;

import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层端到端：刷新 → 状态 → 中文检索（含截断说明字段）→
 * 非法目录刷新返回可区分 400 且旧索引仍可用 → 正确目录刷新恢复。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(ApiEndToEndTest.class);

    @Autowired
    MockMvc mvc;

    @TempDir
    Path root;

    @Test
    void refreshStatusSearchChain() throws Exception {
        Files.writeString(root.resolve("OrderService.java"),
                "// 订单创建入口，处理用户下单请求\n"
                        + "class OrderService {\n"
                        + "    // 订单状态流转与支付回调\n"
                        + "    void create() {}\n"
                        + "}\n");
        Files.writeString(root.resolve("notes.md"), "# 部署说明\n\n订单服务部署步骤与回滚方案。\n");

        // 1. 刷新
        MvcResult refresh = mvc.perform(post("/api/index/refresh")
                        .param("rootDir", root.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(greaterThan(0)))
                .andExpect(jsonPath("$.indexedTotal").value(2))
                .andReturn();
        log.info("[端到端-刷新] 输入=rootDir={}; 判定依据: 200 且 indexedTotal=2, 响应={}",
                root, refresh.getResponse().getContentAsString());

        // 2. 状态
        mvc.perform(get("/api/index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedCount").value(2))
                .andExpect(jsonPath("$.rootDir").value(root.toString()));
        log.info("[端到端-状态] 判定依据: indexedCount=2, rootDir 与刷新目录一致");

        // 3. 中文检索：响应必须带命中总数/返回数/截断说明字段
        MvcResult search = mvc.perform(get("/api/search")
                        .param("q", "订单")
                        .param("budget", "4000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(greaterThan(0)))
                .andExpect(jsonPath("$.returnedHits").value(greaterThan(0)))
                .andExpect(jsonPath("$.hitLimit").exists())
                .andExpect(jsonPath("$.truncatedByHitLimit").exists())
                .andExpect(jsonPath("$.hitLimitRule").exists())
                .andExpect(jsonPath("$.included[0].chunk.path").value("OrderService.java"))
                .andReturn();
        log.info("[端到端-中文检索] 输入=q='订单'; 判定依据: totalHits>0 且首命中 OrderService.java, 响应={}",
                search.getResponse().getContentAsString());

        // 4. 非法目录刷新：三种原因可区分，且返回 400
        mvc.perform(post("/api/index/refresh")
                        .param("rootDir", root.resolve("不存在的目录").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOT_EXISTS"));
        mvc.perform(post("/api/index/refresh")
                        .param("rootDir", root.resolve("notes.md").toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOT_A_DIRECTORY"));
        log.info("[端到端-失败刷新] 输入=不存在目录/普通文件; 判定依据: 400 且 error 分别为 "
                + "NOT_EXISTS / NOT_A_DIRECTORY");

        // 5. 失败刷新后旧索引仍可用
        mvc.perform(get("/api/index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedCount").value(2));
        mvc.perform(get("/api/search").param("q", "订单"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalHits").value(greaterThan(0)));
        log.info("[端到端-旧索引可用] 判定依据: 失败刷新后 indexedCount 仍为2, 中文检索仍命中");

        // 6. 换回正确目录刷新恢复正常
        mvc.perform(post("/api/index/refresh").param("rootDir", root.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedTotal").value(2));
        log.info("[端到端-恢复] 判定依据: 正确目录再次刷新返回 200 且 indexedTotal=2");
    }
}
