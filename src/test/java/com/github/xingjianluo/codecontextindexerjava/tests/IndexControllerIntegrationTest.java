package com.github.xingjianluo.codecontextindexerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 协议端到端：刷新、状态、检索、配置端点可用；
 * 单根约束被违反时返回 409 且错误信息可解释。
 */
@SpringBootTest(properties = {
        "cci.supported-extensions=java,txt"
})
@AutoConfigureMockMvc
class IndexControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fullHttpLifecycleAndRootConflict(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Hello.java"), "class Hello {}\n");
        Files.writeString(root.resolve("ignore.dat"), "binary-ish\n");

        // 刷新（通过参数指定根，覆盖空白的 cci.root）。
        String refreshBody = mockMvc.perform(post("/api/index/refresh")
                        .param("root", root.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.indexedFileCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode refresh = json.readTree(refreshBody);
        TestJudge.info("http-refresh", root.toString(),
                "refresh 返回差异与拒绝；.dat 必须以 UNSUPPORTED_EXTENSION 拒绝",
                "added=" + refresh.get("added") + ", rejected=" + refresh.get("rejected"));

        // 状态端点：拒绝原因可区分。
        mockMvc.perform(get("/api/index/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.initialized").value(true))
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.rejected[0].reason").value("UNSUPPORTED_EXTENSION"))
                .andExpect(jsonPath("$.rejected[0].relativePath").value("ignore.dat"));

        // 检索端点：命中并返回片段。
        mockMvc.perform(get("/api/index/search")
                        .param("q", "Hello")
                        .param("budget", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(1))
                .andExpect(jsonPath("$.totalHits").value(1))
                .andExpect(jsonPath("$.fragments[0].relativePath").value("Hello.java"));

        // 配置端点：生效配置可解释。
        mockMvc.perform(get("/api/index/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxFileBytes").exists())
                .andExpect(jsonPath("$.supportedExtensions[0]").value("java"));

        // 对不同根目录刷新违反单根约束 → 409。
        Path other = Files.createTempDirectory("other-root");
        mockMvc.perform(post("/api/index/refresh").param("root", other.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists())
                .andDo(result -> TestJudge.info("http-root-conflict", other.toString(),
                        "不同根目录并发/后续刷新必须被拒绝",
                        result.getResponse().getContentAsString()));

        // cci.root 为空且刷新不带 root → 409，且错误信息明确（不允许隐式使用）。
        mockMvc.perform(post("/api/index/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists())
                .andDo(result -> TestJudge.info("http-no-root", "(no root param, cci.root blank)",
                        "缺少根目录必须明确拒绝",
                        result.getResponse().getContentAsString()));

        // 带同一根目录重复刷新 → 幂等成功，世代递增、计数不变。
        mockMvc.perform(post("/api/index/refresh").param("root", root.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation").value(2))
                .andExpect(jsonPath("$.indexedFileCount").value(1));
    }
}
