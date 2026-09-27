package com.evops.ticket.sensitive.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词管理端 + 检票拦截端到端（真实 HTTP 层，独立内存库）：
 * 1) 检票备注含高危词：接口 success=false 拦截，票维持 SOLD；
 * 2) 管理端词库查询/新增（同形拒绝）/停用启用/试扫/留痕分页；
 * 3) 新增词热刷新后立即参与扫描；
 * 4) 非 ADMIN 岗位访问敏感词管理端统一 403。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SensitiveAdminWebTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:sensitive-web-test;DB_CLOSE_DELAY=-1;MODE=MySQL");
    }

    /** 桩掉平台出站与通知通道，避免检票触发外部投递噪音 */
    @MockBean private com.evops.ticket.agent.transport.AgentTransportClient agentTransport;
    @MockBean private com.evops.ticket.agent.service.AgentMessageRetryJob agentRetryJob;
    @MockBean private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;
    @MockBean private com.evops.ticket.notify.channel.EmailChannelSender emailChannelSender;

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;

    private static final ObjectMapper JSON = new ObjectMapper();

    private String base() {
        return "http://localhost:" + port;
    }

    private String login(String username) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", "Venue@2026");
        ResponseEntity<JsonNode> resp = rest.postForEntity(
                base() + "/api/auth/login", body, JsonNode.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "登录失败：" + resp.getBody());
        return resp.getBody().path("data").path("accessToken").asText();
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private ResponseEntity<String> exchange(String path, HttpMethod method, String token, Object body) {
        return rest.exchange(base() + path, method, new HttpEntity<>(body, headers(token)), String.class);
    }

    @Test
    void fullFlow_blockCheck_wordAdmin_previewAndHits_nonAdminForbidden() throws Exception {
        String admin = login("venue_admin");
        String checker = login("ticket_check01");

        // ---- 1) 检票高危拦截：检票岗对样本 SOLD 票提交高危备注 ----
        Map<String, Object> checkBody = new LinkedHashMap<>();
        checkBody.put("reason", "黄牛带人走侧门");
        ResponseEntity<String> blocked = exchange(
                "/api/ticket/tickets/TSH001000000001/check", HttpMethod.POST, checker, checkBody);
        assertEquals(HttpStatus.OK, blocked.getStatusCode());
        JsonNode blockedJson = JSON.readTree(blocked.getBody());
        assertFalse(blockedJson.path("success").asBoolean());
        assertTrue(blockedJson.path("message").asText().contains("黄牛"), blocked.getBody());
        // 票状态未变
        JsonNode ticket = JSON.readTree(
                exchange("/api/ticket/tickets/TSH001000000001", HttpMethod.GET, checker, null).getBody());
        assertEquals("SOLD", ticket.path("data").path("status").asText());

        // ---- 2) 词库列表：V7 种子 22 条生效词 ----
        ResponseEntity<String> wordsResp = exchange(
                "/api/ticket/sensitive/words?status=ENABLED", HttpMethod.GET, admin, null);
        JsonNode words = JSON.readTree(wordsResp.getBody());
        assertTrue(words.path("success").asBoolean());
        assertEquals(22, words.path("data").size());

        // ---- 3) 试扫：全角/插缝干扰归一，回显位置 ----
        Map<String, Object> previewBody = new LinkedHashMap<>();
        previewBody.put("text", "加 微 Ｘ");
        // 「加 微」命中低危词「加微」（归一跳过空格）；Ｘ 单独不构成词
        ResponseEntity<String> previewResp = exchange(
                "/api/ticket/sensitive/preview", HttpMethod.POST, admin, previewBody);
        JsonNode preview = JSON.readTree(previewResp.getBody());
        assertTrue(preview.path("success").asBoolean(), previewResp.getBody());
        JsonNode hits = preview.path("data").path("hits");
        assertEquals(1, hits.size());
        assertEquals("加微", hits.get(0).path("word").asText());
        assertEquals("LOW", hits.get(0).path("level").asText());
        assertEquals(0, hits.get(0).path("startCp").asInt());
        assertEquals(3, hits.get(0).path("endCp").asInt(), "区间覆盖中间的空格");

        // ---- 4) 新增词条：热刷新后立刻参与扫描 ----
        Map<String, Object> newWord = new LinkedHashMap<>();
        newWord.put("word", "暗号测试词");
        newWord.put("level", "HIGH");
        newWord.put("category", "FRAUD");
        ResponseEntity<String> addResp = exchange(
                "/api/ticket/sensitive/words", HttpMethod.POST, admin, newWord);
        JsonNode added = JSON.readTree(addResp.getBody());
        assertTrue(added.path("success").asBoolean(), addResp.getBody());
        assertEquals("暗号测试词", added.path("data").path("word").asText());
        assertEquals("暗号测试词", added.path("data").path("normalizedWord").asText());

        // 同形（全角/变体）重复入库拒绝
        Map<String, Object> dupWord = new LinkedHashMap<>();
        dupWord.put("word", "暗号 测试词");
        dupWord.put("level", "LOW");
        ResponseEntity<String> dupResp = exchange(
                "/api/ticket/sensitive/words", HttpMethod.POST, admin, dupWord);
        JsonNode dup = JSON.readTree(dupResp.getBody());
        assertFalse(dup.path("success").asBoolean());
        assertTrue(dup.path("message").asText().contains("同形词"));

        // 热刷新即时生效：用新词再检票，被拦截
        Map<String, Object> checkBody2 = new LinkedHashMap<>();
        checkBody2.put("reason", "对上暗号测试词进场");
        ResponseEntity<String> blocked2 = exchange(
                "/api/ticket/tickets/TSH001000000002/check", HttpMethod.POST, checker, checkBody2);
        JsonNode blocked2Json = JSON.readTree(blocked2.getBody());
        assertFalse(blocked2Json.path("success").asBoolean());
        assertTrue(blocked2Json.path("message").asText().contains("暗号测试词"));

        // ---- 5) 停用新词后该写法放行（票 003 检入成功），重新启用后再次拦截 ----
        long newWordId = added.path("data").path("id").asLong();
        assertEquals(HttpStatus.OK,
                exchange("/api/ticket/sensitive/words/" + newWordId + "/disable",
                        HttpMethod.POST, admin, null).getStatusCode());
        Map<String, Object> checkBody3 = new LinkedHashMap<>();
        checkBody3.put("reason", "对上暗号测试词进场");
        ResponseEntity<String> ok = exchange(
                "/api/ticket/tickets/TSH001000000003/check", HttpMethod.POST, checker, checkBody3);
        assertTrue(JSON.readTree(ok.getBody()).path("success").asBoolean(), ok.getBody());

        // ---- 6) 命中留痕：前两次高危拦截可按场景/词回查（含原文与位置） ----
        ResponseEntity<String> hitsResp = exchange(
                "/api/ticket/sensitive/hits?scene=CHECK&action=BLOCKED&page=1&size=20",
                HttpMethod.GET, admin, null);
        JsonNode hitPage = JSON.readTree(hitsResp.getBody());
        assertTrue(hitPage.path("success").asBoolean());
        assertTrue(hitPage.path("data").path("total").asInt() >= 2);
        boolean sawSeedWord = false;
        boolean sawNewWord = false;
        for (JsonNode row : hitPage.path("data").path("list")) {
            assertEquals("CHECK", row.path("scene").asText());
            assertEquals("BLOCKED", row.path("action").asText());
            assertTrue(row.path("endCp").asInt() > row.path("startCp").asInt());
            if ("黄牛".equals(row.path("word").asText())) {
                sawSeedWord = true;
                assertEquals("黄牛带人走侧门", row.path("originalText").asText());
            }
            if ("暗号测试词".equals(row.path("word").asText())) {
                sawNewWord = true;
            }
        }
        assertTrue(sawSeedWord, "应查到种子词「黄牛」的拦截留痕");
        assertTrue(sawNewWord, "应查到新增词「暗号测试词」的拦截留痕");

        // ---- 7) 非 ADMIN 访问管理端：403 ----
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/api/ticket/sensitive/words", HttpMethod.GET, checker, null).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/api/ticket/sensitive/hits", HttpMethod.GET, checker, null).getStatusCode());
        Map<String, Object> forbiddenWord = new LinkedHashMap<>();
        forbiddenWord.put("word", "越权词");
        forbiddenWord.put("level", "HIGH");
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/api/ticket/sensitive/words", HttpMethod.POST, checker, forbiddenWord).getStatusCode());
    }
}
