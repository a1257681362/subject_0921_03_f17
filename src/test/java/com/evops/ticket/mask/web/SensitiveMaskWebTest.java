package com.evops.ticket.mask.web;

import com.evops.ticket.agent.service.AgentMessageRetryJob;
import com.evops.ticket.agent.transport.AgentTransportClient;
import com.evops.ticket.mask.config.MaskProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检票数据出系统脱敏全链路（真实 HTTP 层，独立内存库）：
 * 1) 未认证 401；
 * 2) 普通 STAFF：接口/幂等回放/导出 看到的都是打码值；
 * 3) 白名单 STAGE_STAFF：接口/导出看到完整值，且按 API/EXPORT 通道分别留痕；
 * 4) 幂等快照跨角色：快照库内存完整值，回放按「当次请求人角色」重脱敏；
 * 5) 非 ADMIN 管理规则 403；ADMIN 发布新版本立即生效、历史版本留档；
 * 6) 导出行数硬上限只能调小。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SensitiveMaskWebTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:mask-test;DB_CLOSE_DELAY=-1;MODE=MySQL");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private MaskProperties maskProperties;
    @MockBean
    private AgentTransportClient transport;
    @MockBean
    private AgentMessageRetryJob agentRetryJob;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAME = "周洋";
    private static final String PHONE = "13812348000";

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
        return headers(token, null);
    }

    private HttpHeaders headers(String token, String requestId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        return h;
    }

    /** 创建预约（可带固定请求号），返回原始 JSON 响应体。 */
    private String createAppointment(String token, String requestId, String day) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seatId", 1);
        body.put("startTime", "2026-10-" + day + "T10:00:00");
        body.put("endTime", "2026-10-" + day + "T10:30:00");
        body.put("visitorName", NAME);
        body.put("contact", PHONE);
        HttpHeaders h = headers(token);
        if (requestId != null) {
            h.set("X-Request-Id", requestId);
        }
        ResponseEntity<String> resp = rest.postForEntity(
                base() + "/api/ticket/checkin/appointments",
                new HttpEntity<>(body, h), String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "创建预约失败：" + resp.getBody());
        assertTrue(resp.getBody().contains("\"success\":true"), resp.getBody());
        return resp.getBody();
    }

    private ResponseEntity<String> get(String path, String token) {
        return rest.exchange(base() + path, org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers(token)), String.class);
    }

    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void noToken_isUnauthorized() {
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/ticket/checkin/appointments", null).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/ticket/checkin/appointments/export", null).getStatusCode());
    }

    @Test
    @Order(2)
    void staff_seesMaskedEverywhere_andNoPlaintextLeak() {
        String staff = login("box_office01");

        // 创建响应即打码
        String created = createAppointment(staff, "MASK-STAFF-CREATE-1", "05");
        assertTrue(created.contains("\"visitorName\":\"周*\""), "姓名应打码：" + created);
        assertTrue(created.contains("\"contact\":\"138****8000\""), "联系方式应打码：" + created);
        assertFalse(created.contains(NAME), "不得泄露姓名明文：" + created);
        assertFalse(created.contains(PHONE), "不得泄露联系方式明文：" + created);

        // 幂等回放：同 requestId 再来一次，仍是打码值
        ResponseEntity<String> replay = rest.postForEntity(
                base() + "/api/ticket/checkin/appointments",
                new HttpEntity<>(replayBody("05"), headers(staff, "MASK-STAFF-CREATE-1")),
                String.class);
        assertEquals(HttpStatus.OK, replay.getStatusCode());
        assertTrue(replay.getBody().contains("\"visitorName\":\"周*\""), "回放也必须打码：" + replay.getBody());
        assertFalse(replay.getBody().contains(PHONE), "回放不得泄露明文：" + replay.getBody());

        // 分页列表打码
        String page = get("/api/ticket/checkin/appointments?page=1&size=20", staff).getBody();
        assertTrue(page.contains("周*") && page.contains("138****8000"), "列表应打码：" + page);
        assertFalse(page.contains(PHONE), "列表不得泄露明文：" + page);

        // 导出 CSV 打码（口径与接口一致）
        ResponseEntity<byte[]> csv = rest.exchange(
                base() + "/api/ticket/checkin/appointments/export",
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers(staff)), byte[].class);
        assertEquals(HttpStatus.OK, csv.getStatusCode());
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertTrue(text.contains("周*") && text.contains("138****8000"), "导出应打码：" + text);
        assertFalse(text.contains(PHONE), "导出不得泄露明文");
    }

    @Test
    @Order(3)
    void whitelistStage_seesPlaintext_andIsAuditedPerChannel() {
        String stage = login("stage01");

        // 白名单场务：接口看到完整值
        String created = createAppointment(stage, "MASK-STAGE-CREATE-1", "06");
        assertTrue(created.contains("\"visitorName\":\"周洋\""), "白名单应见姓名明文：" + created);
        assertTrue(created.contains("\"contact\":\"13812348000\""), "白名单应见联系方式明文：" + created);

        String page = get("/api/ticket/checkin/appointments?page=1&size=20", stage).getBody();
        assertTrue(page.contains("\"visitorName\":\"周洋\""), "白名单列表应见明文：" + page);

        // 导出 CSV 同样是明文（口径一致）
        ResponseEntity<byte[]> csv = rest.exchange(
                base() + "/api/ticket/checkin/appointments/export",
                org.springframework.http.HttpMethod.GET, new HttpEntity<>(headers(stage)), byte[].class);
        assertEquals(HttpStatus.OK, csv.getStatusCode());
        String text = new String(csv.getBody(), StandardCharsets.UTF_8);
        assertTrue(text.contains("周洋") && text.contains("13812348000"), "白名单导出应见明文：" + text);

        // 留痕：API 通道（创建/列表等）与 EXPORT 通道分别可查
        String admin = login("venue_admin");
        JsonNode apiLogs = viewLogs(admin, "stage01", "API");
        assertTrue(apiLogs.path("total").asLong() >= 2, "接口查看应留痕：" + apiLogs);
        JsonNode exportLogs = viewLogs(admin, "stage01", "EXPORT");
        assertTrue(exportLogs.path("total").asLong() >= 2, "导出明文应按 EXPORT 留痕：" + exportLogs);
        // 留痕内容可定位到人、字段、业务单据、规则版本；姓名/联系方式两类字段都应在
        boolean hasName = false;
        boolean hasContact = false;
        for (JsonNode row : exportLogs.path("list")) {
            assertEquals("stage01", row.path("username").asText());
            assertEquals("STAGE_STAFF", row.path("roleCode").asText());
            assertEquals("CheckinAppointment", row.path("bizType").asText());
            assertTrue(row.path("bizId").asText().startsWith("APT"), "留痕应带预约号：" + row);
            assertEquals(1, row.path("ruleVersion").asInt());
            hasName |= "visitorName".equals(row.path("fieldName").asText());
            hasContact |= "contact".equals(row.path("fieldName").asText());
        }
        assertTrue(hasName && hasContact, "姓名/联系方式都应留痕：" + exportLogs);
    }

    @Test
    @Order(4)
    void idempotentSnapshot_reMasksByReplayCallerRole() {
        String staff = login("box_office01");
        String stage = login("stage01");

        // 场务（白名单）创建 → 明文；普通员工用同一 requestId 回放 → 打码
        createAppointment(stage, "MASK-CROSS-1", "07");
        ResponseEntity<String> staffReplay = rest.postForEntity(
                base() + "/api/ticket/checkin/appointments",
                new HttpEntity<>(replayBody("07"), headers(staff, "MASK-CROSS-1")),
                String.class);
        assertTrue(staffReplay.getBody().contains("周*"), "非白名单回放必须打码：" + staffReplay.getBody());
        assertFalse(staffReplay.getBody().contains(PHONE), "非白名单回放不得见明文");

        // 普通员工创建 → 打码；场务用同一 requestId 回放 → 明文并留痕
        createAppointment(staff, "MASK-CROSS-2", "08");
        ResponseEntity<String> stageReplay = rest.postForEntity(
                base() + "/api/ticket/checkin/appointments",
                new HttpEntity<>(replayBody("08"), headers(stage, "MASK-CROSS-2")),
                String.class);
        assertTrue(stageReplay.getBody().contains("\"visitorName\":\"周洋\""),
                "白名单回放应见明文：" + stageReplay.getBody());
    }

    @Test
    @Order(5)
    void nonAdminCannotAdministerRules() {
        String staff = login("box_office01");
        assertEquals(HttpStatus.FORBIDDEN,
                get("/api/ticket/mask/rules", staff).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                get("/api/ticket/mask/view-logs", staff).getStatusCode());

        String stage = login("stage01");
        ResponseEntity<String> publish = rest.postForEntity(
                base() + "/api/ticket/mask/rules/publish",
                new HttpEntity<>(publishBody(java.util.Arrays.asList("STAGE_STAFF")), headers(stage)),
                String.class);
        assertEquals(HttpStatus.FORBIDDEN, publish.getStatusCode());

        // ADMIN 可查当前版本（初始 v1）
        String admin = login("venue_admin");
        String current = get("/api/ticket/mask/rules/current", admin).getBody();
        assertTrue(current.contains("\"ruleVersion\":1") && current.contains("ACTIVE"), current);
    }

    @Test
    @Order(6)
    void adminPublishesNewVersion_effectiveImmediately_historyKept() {
        String admin = login("venue_admin");
        String stage = login("stage01");

        // 发布 v2：白名单收口为空（全员打码）
        ResponseEntity<String> v2 = rest.postForEntity(
                base() + "/api/ticket/mask/rules/publish",
                new HttpEntity<>(publishBody(java.util.Collections.emptyList()), headers(admin)),
                String.class);
        assertEquals(HttpStatus.OK, v2.getStatusCode(), v2.getBody());
        assertTrue(v2.getBody().contains("\"ruleVersion\":2") && v2.getBody().contains("ACTIVE"));

        // 立即生效：白名单场务此刻也只能看到打码值，且不产生新的明文留痕
        String page = get("/api/ticket/checkin/appointments?page=1&size=20", stage).getBody();
        assertTrue(page.contains("周*"), "收口后场务应见打码值：" + page);
        assertFalse(page.contains("\"visitorName\":\"周洋\""), "收口后不得见明文：" + page);

        // 历史版本留档：v1 SUPERSEDED、v2 ACTIVE
        String versions = get("/api/ticket/mask/rules", admin).getBody();
        assertTrue(versions.contains("\"ruleVersion\":2") && versions.contains("\"ruleVersion\":1"), versions);
        assertTrue(versions.contains("SUPERSEDED"), "旧版本应留档为 SUPERSEDED：" + versions);

        // 恢复 v3：白名单放回场务，保证规则服务正常迭代（验证可反复发布）
        ResponseEntity<String> v3 = rest.postForEntity(
                base() + "/api/ticket/mask/rules/publish",
                new HttpEntity<>(publishBody(java.util.Arrays.asList("STAGE_STAFF", "ADMIN")), headers(admin)),
                String.class);
        assertTrue(v3.getBody().contains("\"ruleVersion\":3"), v3.getBody());
        String restored = get("/api/ticket/checkin/appointments?page=1&size=20", stage).getBody();
        assertTrue(restored.contains("\"visitorName\":\"周洋\""), "恢复后场务应再见明文：" + restored);
    }

    @Test
    @Order(7)
    void exportRowLimit_canOnlyTighten() {
        maskProperties.setExportMaxRows(999999);
        assertEquals(5000, maskProperties.effectiveExportMaxRows(), "硬上限 5000 不可调大");
        maskProperties.setExportMaxRows(100);
        assertEquals(100, maskProperties.effectiveExportMaxRows(), "允许调小");
        maskProperties.setExportMaxRows(5000);
    }

    // ------------------------------------------------------------------

    private JsonNode viewLogs(String adminToken, String username, String channel) {
        String body = get("/api/ticket/mask/view-logs?username=" + username
                + "&channel=" + channel + "&page=1&size=100", adminToken).getBody();
        try {
            return JSON.readTree(body).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> publishBody(java.util.List<String> roles) {
        Map<String, Object> body = new LinkedHashMap<>();
        java.util.List<Map<String, String>> rules = new java.util.ArrayList<>();
        Map<String, String> name = new LinkedHashMap<>();
        name.put("field", "visitorName");
        name.put("maskType", "NAME");
        Map<String, String> contact = new LinkedHashMap<>();
        contact.put("field", "contact");
        contact.put("maskType", "CONTACT");
        rules.add(name);
        rules.add(contact);
        body.put("fieldRules", rules);
        body.put("whitelistRoles", roles);
        body.put("remark", "集成测试发布");
        return body;
    }

    /**
     * 幂等回放请求体：与创建完全一致（requestHash 相同才命中快照）。requestId 走
     * X-Request-Id 头，由调用方在 headers(token, requestId) 中给出。
     */
    private Map<String, Object> replayBody(String day) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seatId", 1);
        body.put("startTime", "2026-10-" + day + "T10:00:00");
        body.put("endTime", "2026-10-" + day + "T10:30:00");
        body.put("visitorName", NAME);
        body.put("contact", PHONE);
        return body;
    }
}
