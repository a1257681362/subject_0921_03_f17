package com.evops.auth.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌轮换与账号停用失效链路（真实 HTTP 层，独立内存库；不使用任何替身）：
 * 1) 刷新轮换后旧访问令牌立即 401（缺陷回归：修复前旧 access 在 15 分钟内仍 200）；
 * 2) 轮换链连续两次刷新，每一代旧 access 都被拉黑；新 access 正常；
 * 3) 旧刷新令牌重放 401 且家族连坐，链上其余 refresh 全部作废；
 * 4) 两台设备（两个家族）互不影响：一台轮换/连坐不波及另一台；
 * 5) ADMIN 停用账号：该账号旧 access、refresh 立即 401，登录也被拒；
 * 6) 重新启用后必须重新登录，停用前的旧 access/refresh 不复活；
 * 7) 非 ADMIN 调停用端点 403；ADMIN 不能停用自己（防自锁）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthTokenRotationWebTest {

    private static final String PASSWORD = "Venue@2026";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:auth-rotation-test;DB_CLOSE_DELAY=-1;MODE=MySQL");
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();

    @org.junit.jupiter.api.BeforeEach
    void useBufferingRequestFactory() {
        // 默认 SimpleClientHttpRequestFactory 会开启定长流式输出（setFixedLengthStreamingMode），
        // JDK 8 对流式 POST 收到 401 一律抛 HttpRetryException（请求体已发送无法带认证重发），
        // 反而拿不到服务端真实的 401 响应。关闭 outputStreaming：请求体由 JDK 缓冲、可重放，
        // /refresh 重放/停用返回的 401 即可原样断言。
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setOutputStreaming(false);
        rest.getRestTemplate().setRequestFactory(factory);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    // ---------- 基础设施 ----------

    private static class Pair {
        String access;
        String refresh;
    }

    private Pair login(String username) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", PASSWORD);
        ResponseEntity<JsonNode> resp = rest.postForEntity(
                base() + "/api/auth/login", body, JsonNode.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "登录失败：" + resp.getBody());
        Pair p = new Pair();
        p.access = resp.getBody().path("data").path("accessToken").asText();
        p.refresh = resp.getBody().path("data").path("refreshToken").asText();
        return p;
    }

    private ResponseEntity<JsonNode> refresh(String rawRefresh) {
        Map<String, String> body = Collections.singletonMap("refreshToken", rawRefresh);
        return rest.postForEntity(base() + "/api/auth/refresh", body, JsonNode.class);
    }

    private ResponseEntity<JsonNode> me(String access) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(access);
        return rest.exchange(base() + "/api/auth/me", HttpMethod.GET,
                new HttpEntity<>(h), JsonNode.class);
    }

    private ResponseEntity<String> callAdmin(String path, HttpMethod method, String adminToken, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(adminToken);
        return rest.exchange(base() + path, method, new HttpEntity<>(body, h), String.class);
    }

    private long userIdOf(String adminToken, String username) {
        ResponseEntity<String> resp = callAdmin(
                "/api/admin/rbac/users?keyword=" + username, HttpMethod.GET, adminToken, null);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "查用户失败：" + resp.getBody());
        return JsonPath.at(resp.getBody(), "$.data.list[0].id").asLong();
    }

    /** 只解码 JWT 负载取字段（测试断言用，不校验签名）。 */
    private JsonNode claims(String accessToken) {
        try {
            String payload = accessToken.split("\\.")[1];
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            return JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String jtiOf(String accessToken) {
        String payload = accessToken.split("\\.")[1];
        return JsonPath.readJti(payload);
    }

    private static String sha256Hex(String raw) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 极简 JSON 路径读取，避免引额外依赖。 */
    private static final class JsonPath {
        static JsonNode at(String json, String path) {
            try {
                JsonNode node = JSON.readTree(json);
                for (String seg : path.replace("$.", "").split("\\.")) {
                    if (seg.contains("[")) {
                        String name = seg.substring(0, seg.indexOf('['));
                        int idx = Integer.parseInt(seg.substring(seg.indexOf('[') + 1, seg.indexOf(']')));
                        node = node.path(name).path(idx);
                    } else {
                        node = node.path(seg);
                    }
                }
                return node;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static String readJti(String b64Payload) {
            try {
                return JSON.readTree(new String(
                        Base64.getUrlDecoder().decode(b64Payload), StandardCharsets.UTF_8)).path("jti").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ---------- 用例 ----------

    /** 用例 1+2：刷新后旧 access 立即失效；连续轮换逐代作废；库内黑名单留痕 ROTATED。 */
    @Test
    void refresh_revokesOldAccessImmediately_andChains() {
        Pair p1 = login("box_office01");
        assertEquals(HttpStatus.OK, me(p1.access).getStatusCode(), "刷新前旧 access 应可用");

        // 第一次轮换
        ResponseEntity<JsonNode> r1 = refresh(p1.refresh);
        assertEquals(HttpStatus.OK, r1.getStatusCode(), "刷新失败：" + r1.getBody());
        Pair p2 = new Pair();
        p2.access = r1.getBody().path("data").path("accessToken").asText();
        p2.refresh = r1.getBody().path("data").path("refreshToken").asText();

        // ★核心回归断言：旧 access 立刻 401
        ResponseEntity<JsonNode> oldMe = me(p1.access);
        assertEquals(HttpStatus.UNAUTHORIZED, oldMe.getStatusCode(),
                "刷新后旧访问令牌必须立即失效，实际：" + oldMe.getStatusCode());
        assertEquals(false, oldMe.getBody().path("success").asBoolean());
        // 新 access 正常
        assertEquals(HttpStatus.OK, me(p2.access).getStatusCode(), "新访问令牌应可用");

        // 黑名单留痕：旧 jti 因 ROTATED 落库
        String oldJti = jtiOf(p1.access);
        Integer blocked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_token_blacklist WHERE jti = ? AND reason = 'ROTATED'",
                Integer.class, oldJti);
        assertEquals(1, blocked, "旧访问令牌应在黑名单且 reason=ROTATED，jti=" + oldJti);
        // 刷新令牌行已登记配对 access
        String registeredJti = jdbc.queryForObject(
                "SELECT access_jti FROM t_refresh_token WHERE status = 'USED' AND access_jti IS NOT NULL "
                        + "ORDER BY id DESC LIMIT 1", String.class);
        assertEquals(oldJti, registeredJti, "被消费 refresh 行应登记被它换掉的旧 access jti");

        // 第二次轮换：第二代旧 access 同样立即作废
        ResponseEntity<JsonNode> r2 = refresh(p2.refresh);
        assertEquals(HttpStatus.OK, r2.getStatusCode());
        String a3 = r2.getBody().path("data").path("accessToken").asText();
        assertEquals(HttpStatus.UNAUTHORIZED, me(p2.access).getStatusCode(),
                "第二次轮换后上一代 access 也必须立即失效");
        assertEquals(HttpStatus.OK, me(a3).getStatusCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_token_blacklist WHERE jti = ? AND reason = 'ROTATED'",
                Integer.class, jtiOf(p2.access)));

        // 旧 refresh 重放 401（放链式轮换之后：重放会家族连坐，提前做会撤销后续链节）
        assertEquals(HttpStatus.UNAUTHORIZED, refresh(p1.refresh).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, refresh(p2.refresh).getStatusCode(),
                "重放连坐后已轮换出的新 refresh 也必须作废");
    }

    /** 用例 3：旧 refresh 重放触发家族连坐，当前在用的 refresh 也被撤销。 */
    @Test
    void replayOldRefresh_revokesWholeFamily() {
        Pair p1 = login("ticket_check01");
        ResponseEntity<JsonNode> r = refresh(p1.refresh);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        String r2 = r.getBody().path("data").path("refreshToken").asText();
        String a2 = r.getBody().path("data").path("accessToken").asText();

        // 旧 refresh 重放
        ResponseEntity<JsonNode> replay = refresh(p1.refresh);
        assertEquals(HttpStatus.UNAUTHORIZED, replay.getStatusCode());
        assertEquals("刷新令牌已失效，请重新登录", replay.getBody().path("message").asText());
        // 连坐：新 refresh 也无法使用
        ResponseEntity<JsonNode> afterReplay = refresh(r2);
        assertEquals(HttpStatus.UNAUTHORIZED, afterReplay.getStatusCode(),
                "重放后同家族 refresh 必须连坐撤销");
        // 连坐：家族内不再有 ACTIVE 刷新令牌
        String familyId = jdbc.queryForObject(
                "SELECT family_id FROM t_refresh_token WHERE token_hash = ?",
                String.class, sha256Hex(p1.refresh));
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_refresh_token WHERE family_id = ? AND status = 'ACTIVE'",
                Integer.class, familyId);
        assertEquals(0, active, "重放连坐后同家族不应再有 ACTIVE 刷新令牌");
    }

    /** 用例 4：两台设备家族互不影响。 */
    @Test
    void twoDeviceFamilies_areIndependent() {
        Pair d1 = login("box_office01");
        Pair d2 = login("box_office01");

        // 设备 1 轮换：旧 access 失效，新 access 可用
        ResponseEntity<JsonNode> r = refresh(d1.refresh);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        String d1NewAccess = r.getBody().path("data").path("accessToken").asText();
        String d1NewRefresh = r.getBody().path("data").path("refreshToken").asText();
        assertEquals(HttpStatus.UNAUTHORIZED, me(d1.access).getStatusCode());
        assertEquals(HttpStatus.OK, me(d1NewAccess).getStatusCode());

        // 设备 2 完全不受影响：access 仍可用，refresh 正常轮换
        assertEquals(HttpStatus.OK, me(d2.access).getStatusCode(),
                "另一台设备的访问令牌不应被波及");
        ResponseEntity<JsonNode> r2 = refresh(d2.refresh);
        assertEquals(HttpStatus.OK, r2.getStatusCode(), "另一台设备的刷新不应被连坐");
        assertEquals(HttpStatus.OK, me(r2.getBody().path("data").path("accessToken").asText())
                .getStatusCode());

        // 设备 1 再制造一次重放连坐，设备 2 的新 refresh 仍可用
        assertEquals(HttpStatus.UNAUTHORIZED, refresh(d1.refresh).getStatusCode());
        ResponseEntity<JsonNode> d2Again = refresh(r2.getBody().path("data").path("refreshToken").asText());
        assertEquals(HttpStatus.OK, d2Again.getStatusCode(), "家族连坐不得波及其他设备");
        assertNotNull(d1NewRefresh);
    }

    /** 用例 5：停用账号，在线令牌与后续登录全部立即失效。 */
    @Test
    void adminDisable_killsAllTokensImmediately() {
        Pair admin = login("venue_admin");
        Pair target = login("stage01");
        long targetId = userIdOf(admin.access, "stage01");

        assertEquals(HttpStatus.OK, me(target.access).getStatusCode(), "停用前应正常");

        ResponseEntity<String> disabled = callAdmin(
                "/api/admin/rbac/users/" + targetId + "/disable", HttpMethod.POST, admin.access, null);
        assertEquals(HttpStatus.OK, disabled.getStatusCode(), "停用接口调用失败：" + disabled.getBody());

        // 旧 access 下一请求即 401
        ResponseEntity<JsonNode> oldAccess = me(target.access);
        assertEquals(HttpStatus.UNAUTHORIZED, oldAccess.getStatusCode());
        assertEquals("账号不可用，请重新登录", oldAccess.getBody().path("message").asText());
        // refresh 也 401
        assertEquals(HttpStatus.UNAUTHORIZED, refresh(target.refresh).getStatusCode());
        // 连密码登录都被拒
        Map<String, String> loginBody = new LinkedHashMap<>();
        loginBody.put("username", "stage01");
        loginBody.put("password", PASSWORD);
        ResponseEntity<JsonNode> loginWhileDisabled =
                rest.postForEntity(base() + "/api/auth/login", loginBody, JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED, loginWhileDisabled.getStatusCode());

        // 库内：状态 DISABLED、tv 已推进、该用户 refresh 全部不再 ACTIVE
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, token_version FROM t_admin_user WHERE id = ?", targetId);
        assertEquals("DISABLED", row.get("STATUS"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_refresh_token WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, targetId));

        // 清理：重新启用，避免污染同库其他用例
        ResponseEntity<String> enabled = callAdmin(
                "/api/admin/rbac/users/" + targetId + "/enable", HttpMethod.POST, admin.access, null);
        assertEquals(HttpStatus.OK, enabled.getStatusCode());
    }

    /** 用例 6：重启用后旧令牌不复活，必须用密码重新登录。 */
    @Test
    void reenable_oldTokensStayDead_freshLoginWorks() {
        Pair admin = login("venue_admin");
        long targetId = userIdOf(admin.access, "multi_ops01");
        Pair target = login("multi_ops01");
        String oldAccess = target.access;
        String oldRefresh = target.refresh;

        callAdmin("/api/admin/rbac/users/" + targetId + "/disable", HttpMethod.POST, admin.access, null);
        assertEquals(HttpStatus.UNAUTHORIZED, me(oldAccess).getStatusCode());

        callAdmin("/api/admin/rbac/users/" + targetId + "/enable", HttpMethod.POST, admin.access, null);

        // 停用前的旧 access：tv 已推进，仍 401
        ResponseEntity<JsonNode> oldAccessAfter = me(oldAccess);
        assertEquals(HttpStatus.UNAUTHORIZED, oldAccessAfter.getStatusCode(),
                "重启用不得让停用前的旧访问令牌复活");
        // 停用前的旧 refresh：已被撤销，仍 401
        assertEquals(HttpStatus.UNAUTHORIZED, refresh(oldRefresh).getStatusCode(),
                "重启用不得让停用前的旧刷新令牌复活");
        // 重新用密码登录成功，拿到全新令牌对
        Pair fresh = login("multi_ops01");
        assertEquals(HttpStatus.OK, me(fresh.access).getStatusCode());
        ResponseEntity<JsonNode> refreshed = refresh(fresh.refresh);
        assertEquals(HttpStatus.OK, refreshed.getStatusCode());
    }

    /** 用例 7：权限边界——非 ADMIN 403；ADMIN 不能停用自己。 */
    @Test
    void disablePermission_guarded() throws Exception {
        Pair staff = login("box_office01");
        Pair admin = login("venue_admin");
        long staffId = userIdOf(admin.access, "box_office01");

        ResponseEntity<String> forbidden = callAdmin(
                "/api/admin/rbac/users/" + staffId + "/disable", HttpMethod.POST, staff.access, null);
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode(),
                "非 ADMIN 停用账号必须 403，实际：" + forbidden.getStatusCode());

        long adminId = userIdOf(admin.access, "venue_admin");
        ResponseEntity<String> self = callAdmin(
                "/api/admin/rbac/users/" + adminId + "/disable", HttpMethod.POST, admin.access, null);
        JsonNode selfBody = JSON.readTree(self.getBody());
        assertEquals(false, selfBody.path("success").asBoolean(),
                "停用自己应被拒绝，实际：" + self.getBody());
        assertEquals("不能停用当前登录账号（防止自锁）", selfBody.path("message").asText());
        // 管理员账号仍为 ACTIVE
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM t_admin_user WHERE id = ?", String.class, adminId));
    }
}
