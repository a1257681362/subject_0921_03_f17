package com.evops.rbac.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一人多岗 RBAC 全链路（真实 HTTP 层，独立内存库）：
 * 1) /me 与 /api/auth/menus：多岗 roles 并集、菜单树按并集动态装配且去重、权限码并集；
 * 2) 并集非交集：双岗账号两岗的事都能做，并集外（建场次/删座位）403；
 * 3) 覆盖式授岗：单岗时只能做本岗，空岗全收；
 * 4) 停用角色即时收权：旧 JWT 不重登，下一请求即 403，菜单同步收缩，另一岗仍可用；
 * 5) 越权统一 403 并落 t_rbac_deny_log，管理端可查；
 * 6) 管理端仅 ADMIN；ADMIN 内置角色防自锁；自定义角色/菜单节点生命周期。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RbacWebTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:rbac-test;DB_CLOSE_DELAY=-1;MODE=MySQL");
    }

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

    private ResponseEntity<String> get(String path, String token) {
        return exchange(path, HttpMethod.GET, token, null);
    }

    /** 取 multi_ops01 的用户 id（管理端按工号检索）。 */
    private long multiOpsId(String admin) {
        String body = get("/api/admin/rbac/users?keyword=multi_ops01", admin).getBody();
        JsonNode node;
        try {
            node = JSON.readTree(body).path("data").path("list");
        } catch (Exception e) {
            throw new IllegalStateException(body, e);
        }
        assertEquals(1, node.size(), "应只检索到 multi_ops01：" + body);
        return node.get(0).path("id").asLong();
    }

    private long roleId(String admin, String code) {
        String body = get("/api/admin/rbac/roles", admin).getBody();
        try {
            JsonNode roles = JSON.readTree(body).path("data");
            for (JsonNode r : roles) {
                if (code.equals(r.path("code").asText())) {
                    return r.path("id").asLong();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(body, e);
        }
        throw new IllegalStateException("角色不存在：" + code);
    }

    private void assignRoles(String admin, long userId, Long... roleIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("roleIds", java.util.Arrays.asList(roleIds));
        ResponseEntity<String> resp = exchange("/api/admin/rbac/users/" + userId + "/roles",
                HttpMethod.PUT, admin, body);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "授岗失败：" + resp.getBody());
        assertTrue(resp.getBody().contains("\"success\":true"), resp.getBody());
    }

    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void multiRole_meAndMenuTree_areUnion() throws Exception {
        String multi = login("multi_ops01");

        // /me：主岗位 BOX_OFFICE，roles 为两岗并集
        String me = get("/api/auth/me", multi).getBody();
        assertTrue(me.contains("\"role\":\"BOX_OFFICE\""), me);
        JsonNode profile = JSON.readTree(me).path("data");
        List<String> roles = JSON.convertValue(profile.path("roles"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        assertTrue(roles.contains("BOX_OFFICE") && roles.contains("TICKET_CHECK"),
                "一人多岗 roles 应并集：" + me);

        // 菜单树按并集装配：售票（订单管理）与检票（检票作业）两支并存，根目录不重复
        String menus = get("/api/auth/menus", multi).getBody();
        assertTrue(menus.contains("订单管理"), "并集菜单应含售票支：" + menus);
        assertTrue(menus.contains("检票作业"), "并集菜单应含检票支：" + menus);
        // 多岗共有的票务运营目录只出现一次（去重）
        int dirCount = menus.split("票务运营", -1).length - 1;
        assertEquals(1, dirCount, "多岗共有目录不得重复装配：" + menus);
        // 权限码并集：两岗各有一个独有码；并集外的通岗权限不得出现
        assertTrue(menus.contains("ticket:order:view") && menus.contains("ticket:ticket:check"),
                "权限码应取并集：" + menus);
        assertFalse(menus.contains("ticket:seat:create"), "未授通岗，不应出现建座位权限：" + menus);
        assertFalse(menus.contains("ticket:session:create"), "未授通岗，不应出现建场次权限：" + menus);
    }

    @Test
    @Order(2)
    void union_notIntersection_bothRolesWork_outsideUnionForbidden() {
        String multi = login("multi_ops01");

        // 售票岗的事（查订单）+ 检票岗的事（入场检票），并集都能做
        assertEquals(HttpStatus.OK,
                get("/api/ticket/orders/1", multi).getStatusCode());
        ResponseEntity<String> check = exchange("/api/ticket/tickets/TSH001000000005/check",
                HttpMethod.POST, multi, new LinkedHashMap<>());
        assertEquals(HttpStatus.OK, check.getStatusCode(), check.getBody());

        // 两岗并集之外：建场次、删座位，统一 403
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/api/ticket/sessions", HttpMethod.POST, multi, new LinkedHashMap<>())
                        .getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/api/ticket/seats/1", HttpMethod.DELETE, multi, null).getStatusCode());
    }

    @Test
    @Order(3)
    void forbidden_is403_andRecordedInDenyLog() throws Exception {
        String staff = login("box_office01");
        // STAFF+BOX_OFFICE 打脱敏管理端 → 统一 403
        ResponseEntity<String> denied = get("/api/ticket/mask/rules", staff);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertTrue(denied.getBody().contains("\"success\":false"), denied.getBody());

        String admin = login("venue_admin");
        String logs = get("/api/admin/rbac/deny-logs?username=box_office01&size=50", admin).getBody();
        JsonNode page = JSON.readTree(logs).path("data");
        assertTrue(page.path("total").asLong() >= 1, "越权应有留痕：" + logs);
        boolean found = false;
        for (JsonNode row : page.path("list")) {
            if ("/api/ticket/mask/rules".equals(row.path("requestPath").asText())) {
                found = true;
                assertEquals("mask:rule:view", row.path("requiredPermission").asText());
                assertEquals("GET", row.path("requestMethod").asText());
                String rolesCsv = row.path("rolesCsv").asText();
                assertTrue(rolesCsv.contains("STAFF") && rolesCsv.contains("BOX_OFFICE"),
                        "留痕应记当时角色并集：" + rolesCsv);
                assertFalse(row.path("requestId").asText().isEmpty(), "留痕应带请求号");
            }
        }
        assertTrue(found, "应能按工号查到该笔越权记录：" + logs);
    }

    @Test
    @Order(4)
    void adminOnly_andAdminSelfLockGuarded() {
        String staff = login("box_office01");
        // 管理端非 ADMIN 统一 403
        assertEquals(HttpStatus.FORBIDDEN,
                get("/api/admin/rbac/roles", staff).getStatusCode());

        String admin = login("venue_admin");
        assertEquals(HttpStatus.OK, get("/api/admin/rbac/roles", admin).getStatusCode());
        assertEquals(HttpStatus.OK, get("/api/admin/rbac/menus/tree", admin).getStatusCode());

        // ADMIN 内置角色不可停用/删除（防自锁），业务校验 success=false
        long adminRoleId = roleId(admin, "ADMIN");
        ResponseEntity<String> disable = exchange("/api/admin/rbac/roles/" + adminRoleId + "/disable",
                HttpMethod.POST, admin, null);
        assertTrue(disable.getBody().contains("\"success\":false"), "ADMIN 角色应禁止停用：" + disable.getBody());
        ResponseEntity<String> delete = exchange("/api/admin/rbac/roles/" + adminRoleId,
                HttpMethod.DELETE, admin, null);
        assertTrue(delete.getBody().contains("\"success\":false"), "ADMIN 角色应禁止删除：" + delete.getBody());
    }

    @Test
    @Order(5)
    void assignRoles_coverage_singleRoleAndEmpty_thenRestore() {
        String admin = login("venue_admin");
        long uid = multiOpsId(admin);
        long boxOffice = roleId(admin, "BOX_OFFICE");
        long ticketCheck = roleId(admin, "TICKET_CHECK");
        String multi = login("multi_ops01"); // 授岗全程不换 token，靠每请求实时装载生效

        // 只留售票岗：能查订单，检票 403
        assignRoles(admin, uid, boxOffice);
        assertEquals(HttpStatus.OK, get("/api/ticket/orders/1", multi).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN,
                exchange("/api/ticket/tickets/TSH001000000001/check", HttpMethod.POST, multi,
                        new LinkedHashMap<>()).getStatusCode());

        // 只留检票岗：检票 200（业务成败都 200，关键是过了权限关），查订单 403
        assignRoles(admin, uid, ticketCheck);
        assertEquals(HttpStatus.OK,
                exchange("/api/ticket/tickets/TSH001000000001/check", HttpMethod.POST, multi,
                        new LinkedHashMap<>()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, get("/api/ticket/orders/1", multi).getStatusCode());

        // 一键下岗：菜单空树，任何业务端点 403
        assignRoles(admin, uid);
        String menus = get("/api/auth/menus", multi).getBody();
        assertTrue(menus.contains("\"menus\":[]"), "无岗位菜单应为空树：" + menus);
        assertEquals(HttpStatus.FORBIDDEN, get("/api/ticket/orders/1", multi).getStatusCode());

        // 恢复双岗（后续停用即时收权用例依赖）
        assignRoles(admin, uid, boxOffice, ticketCheck);
        assertEquals(HttpStatus.OK, get("/api/ticket/orders/1", multi).getStatusCode());
    }

    @Test
    @Order(6)
    void disableRole_takesEffectImmediately_withOldToken() throws Exception {
        String admin = login("venue_admin");
        long boxOffice = roleId(admin, "BOX_OFFICE");
        String multi = login("multi_ops01"); // 先拿 token，之后全程不重登

        // 停用售票岗
        ResponseEntity<String> disable = exchange("/api/admin/rbac/roles/" + boxOffice + "/disable",
                HttpMethod.POST, admin, null);
        assertTrue(disable.getBody().contains("\"success\":true"), disable.getBody());

        // 旧 token 下一请求：售票权限即时收回
        assertEquals(HttpStatus.FORBIDDEN, get("/api/ticket/orders/1", multi).getStatusCode());
        // 检票岗仍启用：检票不受影响
        assertEquals(HttpStatus.OK,
                exchange("/api/ticket/tickets/TSH001000000002/check", HttpMethod.POST, multi,
                        new LinkedHashMap<>()).getStatusCode());
        // 菜单同步收缩：订单管理消失，检票作业仍在；权限集不再含售票码
        String menus = get("/api/auth/menus", multi).getBody();
        assertFalse(menus.contains("订单管理"), "停用岗位后菜单应即时收缩：" + menus);
        assertTrue(menus.contains("检票作业"), "另一在岗的菜单应保留：" + menus);
        assertFalse(menus.contains("ticket:order:view"), "停用岗位权限码应即时收回：" + menus);

        // 恢复后旧 token 自动恢复，无需重新登录
        exchange("/api/admin/rbac/roles/" + boxOffice + "/enable", HttpMethod.POST, admin, null);
        assertEquals(HttpStatus.OK, get("/api/ticket/orders/1", multi).getStatusCode());
        assertTrue(get("/api/auth/menus", multi).getBody().contains("订单管理"));
    }

    @Test
    @Order(7)
    void customRole_andMenuNode_lifecycle() {
        String admin = login("venue_admin");

        // 自定义角色：新建 → 无人绑定可删 → 删除成功
        Map<String, Object> roleReq = new LinkedHashMap<>();
        roleReq.put("code", "TEMP_ROLE");
        roleReq.put("name", "临时岗位");
        ResponseEntity<String> created = exchange("/api/admin/rbac/roles", HttpMethod.POST, admin, roleReq);
        assertEquals(HttpStatus.OK, created.getStatusCode(), created.getBody());
        long tempRoleId = roleId(admin, "TEMP_ROLE");
        assertEquals(HttpStatus.OK,
                exchange("/api/admin/rbac/roles/" + tempRoleId, HttpMethod.DELETE, admin, null)
                        .getStatusCode());

        // 菜单节点：在票务运营(100)下建临时按钮 → 删除（无角色引用，可直接删）
        Map<String, Object> menuReq = new LinkedHashMap<>();
        menuReq.put("parentId", 100);
        menuReq.put("type", "BUTTON");
        menuReq.put("name", "临时权限点");
        menuReq.put("permission", "ticket:temp:fire");
        menuReq.put("visible", 0);
        ResponseEntity<String> menuCreated = exchange("/api/admin/rbac/menus", HttpMethod.POST, admin, menuReq);
        assertEquals(HttpStatus.OK, menuCreated.getStatusCode(), menuCreated.getBody());
        long menuId;
        try {
            menuId = JSON.readTree(menuCreated.getBody()).path("data").path("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(menuCreated.getBody(), e);
        }
        ResponseEntity<String> menuDeleted = exchange("/api/admin/rbac/menus/" + menuId,
                HttpMethod.DELETE, admin, null);
        assertTrue(menuDeleted.getBody().contains("\"success\":true"), menuDeleted.getBody());

        // 权限码重复应拒绝
        Map<String, Object> dup = new LinkedHashMap<>(menuReq);
        dup.put("permission", "ticket:order:create");
        ResponseEntity<String> dupResp = exchange("/api/admin/rbac/menus", HttpMethod.POST, admin, dup);
        assertTrue(dupResp.getBody().contains("\"success\":false"), "权限码重复应拒绝：" + dupResp.getBody());
    }
}
