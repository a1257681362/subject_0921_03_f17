package com.evops.ticket.attachment.web;

import com.evops.ticket.agent.service.AgentMessageRetryJob;
import com.evops.ticket.agent.transport.AgentTransportClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客服整批下载走查（真实 HTTP 层）：
 * 1) 全部端点无访问令牌 401；
 * 2) 中文文件名上传/列表/单下载（Content-Disposition filename* 编码）；
 * 3) ZIP 流式包：票号目录、中文文件名不乱码、清单列出票号、文件内容一致；
 * 4) 无附件票号在清单中提示；
 * 5) 单包超 50 份开流前拦截（JSON success=false，不产出半截 ZIP）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TicketAttachmentZipWebTest {

    @TempDir
    static Path tempAttachDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // 附件落临时目录，不污染工作区 data/
        registry.add("evops.attachment.storage-dir", () -> tempAttachDir.toString());
        // 独立内存库：与其它 @SpringBootTest 上下文隔离（否则共享同名 mem 库时，
        // 先启动的上下文已把样本附件行播进共享库、物理文件却落在默认目录，
        // 本上下文播种器幂等跳过，造成"元数据在、文件缺失"）
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:attach-test;DB_CLOSE_DELAY=-1;MODE=MySQL");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private AgentTransportClient transport;
    @MockBean
    private AgentMessageRetryJob retryJob;

    private String base;
    private String token;

    private String baseUrl() {
        if (base == null) {
            base = "http://localhost:" + port;
        }
        return base;
    }

    /** 样本播种的 box_office01 账号登录取访问令牌。 */
    private String login() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("username", "box_office01");
        body.put("password", "Venue@2026");
        ResponseEntity<com.fasterxml.jackson.databind.JsonNode> resp = rest.postForEntity(
                baseUrl() + "/api/auth/login", body, com.fasterxml.jackson.databind.JsonNode.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        String accessToken = resp.getBody().path("data").path("accessToken").asText();
        assertTrue(accessToken != null && !accessToken.isEmpty(), "登录应返回 accessToken：" + resp.getBody());
        return accessToken;
    }

    private HttpHeaders authHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void endpoints_withoutToken_areUnauthorized() {
        ResponseEntity<String> list = rest.getForEntity(
                baseUrl() + "/api/ticket/tickets/TSH001000000005/attachments", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, list.getStatusCode());

        ResponseEntity<String> zip = rest.getForEntity(
                baseUrl() + "/api/ticket/attachments/zip?ticketNos=TSH001000000005", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, zip.getStatusCode());
    }

    @Test
    void uploadChineseName_listAndSingleDownload_preserveNameAndContent() {
        token = login();
        String ticketNo = "TSH001000000007"; // 样本中在售后无附件的票
        byte[] content = "附件内容：中文与符号 §☆\r\n第二行".getBytes(StandardCharsets.UTF_8);
        String fileName = "报销凭证_" + ticketNo + ".txt";

        // 上传
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        ResponseEntity<String> up = rest.postForEntity(
                baseUrl() + "/api/ticket/tickets/" + ticketNo + "/attachments",
                new HttpEntity<>(form, headers), String.class);
        assertEquals(HttpStatus.OK, up.getStatusCode(), "上传应成功：" + up.getBody());
        assertTrue(up.getBody() != null && up.getBody().contains("\"success\":true")
                && up.getBody().contains(fileName), "上传响应应含中文名：" + up.getBody());

        // 列表
        ResponseEntity<String> listResp = rest.exchange(
                baseUrl() + "/api/ticket/tickets/" + ticketNo + "/attachments",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertEquals(HttpStatus.OK, listResp.getStatusCode());
        assertTrue(listResp.getBody().contains(fileName));

        long id;
        try {
            com.fasterxml.jackson.databind.JsonNode items =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(listResp.getBody()).path("data");
            id = items.get(items.size() - 1).path("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // 单下载：Content-Disposition 带 filename* UTF-8 编码，内容一致
        ResponseEntity<byte[]> single = rest.exchange(
                baseUrl() + "/api/ticket/attachments/" + id + "/download",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), byte[].class);
        assertEquals(HttpStatus.OK, single.getStatusCode());
        String disposition = single.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains("filename*=UTF-8''"), "应带 RFC5987 编码文件名：" + disposition);
        assertTrue(disposition.contains(urlEncodeUtf8(fileName)),
                "filename* 应为中文 URL 编码：" + disposition);
        assertArrayEquals(content, single.getBody());
    }

    @Test
    void zipStream_hasTicketFolders_utf8ChineseNames_andManifestWithTicketNos() throws Exception {
        token = login();
        // 样本播种：TSH001000000004、TSH002000000001 各有一个中文名附件
        String url = baseUrl() + "/api/ticket/attachments/zip"
                + "?ticketNos=TSH001000000004&ticketNos=TSH002000000001";
        ResponseEntity<byte[]> resp = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), byte[].class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        MediaType contentType = resp.getHeaders().getContentType();
        assertNotNull(contentType);
        assertTrue(contentType.isCompatibleWith(MediaType.parseMediaType("application/zip")),
                "应为 application/zip，实际=" + contentType);
        String disposition = resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.contains("filename*=UTF-8''"), "ZIP 包名也应 UTF-8 编码：" + disposition);

        Map<String, byte[]> entries = readZip(resp.getBody());
        assertTrue(entries.containsKey("TSH001000000004/票根凭证_TSH001000000004（已检票）.txt"),
                "ZIP 条目名应保留中文，实际=" + entries.keySet());
        assertTrue(entries.containsKey("TSH002000000001/天鹅湖票根_TSH002000000001.txt"),
                "ZIP 条目名应保留中文，实际=" + entries.keySet());
        assertTrue(entries.containsKey("下载清单.txt"), "包内必须有下载清单");

        String manifestText = new String(stripBom(entries.get("下载清单.txt")), StandardCharsets.UTF_8);
        assertTrue(manifestText.contains("TSH001000000004"), "清单必须列出票号 TSH001000000004：\n" + manifestText);
        assertTrue(manifestText.contains("TSH002000000001"), "清单必须列出票号 TSH002000000001：\n" + manifestText);
        assertTrue(manifestText.contains("票根凭证_TSH001000000004（已检票）.txt"), "清单应含中文文件名");

        String content = new String(
                entries.get("TSH002000000001/天鹅湖票根_TSH002000000001.txt"), StandardCharsets.UTF_8);
        assertTrue(content.contains("TSH002000000001"));
    }

    @Test
    void zipStream_chineseEntries_areRawUtf8WithEfsFlag_bitLevelRegression() throws Exception {
        // 回归：客服报障"解压后中文文件名全变下划线"。下划线只能由解压/保存端对非 ASCII
        // 做清洗产生；服务端必须保证条目名是 UTF-8 原始字节并置 EFS(bit11)，否则符合标准的
        // 解压端无法还原中文。直接解析 ZIP 中央目录原始字节断言，不依赖任何解压库的解码策略。
        token = login();
        String url = baseUrl() + "/api/ticket/attachments/zip"
                + "?ticketNos=TSH001000000004&ticketNos=TSH002000000001";
        ResponseEntity<byte[]> resp = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), byte[].class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        List<CentralEntry> central = readCentralDirectory(resp.getBody());
        boolean sawChinese = false;
        for (CentralEntry e : central) {
            boolean hasNonAscii = false;
            for (byte b : e.rawName) {
                if ((b & 0x80) != 0) {
                    hasNonAscii = true;
                    break;
                }
            }
            if (hasNonAscii) {
                sawChinese = true;
                // 1) 必须带 EFS 语言标志位（general purpose bit 11 = 0x0800）
                assertEquals(0x800, e.flagBits & 0x800,
                        "含中文（非 ASCII）的条目必须置 EFS 标志位：" + e.rawNameUtf8());
                // 2) 条目名原始字节必须就是 UTF-8 编码，且可无损往返
                byte[] reEncoded = e.rawNameUtf8().getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(e.rawName, reEncoded,
                        "条目名原始字节必须是 UTF-8：" + e.rawNameUtf8());
            }
        }
        assertTrue(sawChinese, "本用例应至少覆盖一个含中文的条目");

        // 3) 关键样本名按 UTF-8 精确还原（票号目录/中文文件名/清单）
        List<String> names = new ArrayList<>();
        for (CentralEntry e : central) {
            names.add(e.rawNameUtf8());
        }
        assertTrue(names.contains("TSH002000000001/天鹅湖票根_TSH002000000001.txt"),
                "UTF-8 条目名应精确还原，实际=" + names);
        assertTrue(names.contains("下载清单.txt"), "清单条目名也应为中文 UTF-8");
    }

    @Test
    void zipStream_withTicketHavingNoAttachment_listsItInManifest() throws Exception {
        token = login();
        String url = baseUrl() + "/api/ticket/attachments/zip"
                + "?ticketNos=TSH001000000005&ticketNos=TSH001000000002"; // 005 有附件，002 无
        ResponseEntity<byte[]> resp = rest.exchange(url, HttpMethod.GET,
                new HttpEntity<>(authHeaders()), byte[].class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, byte[]> entries = readZip(resp.getBody());
        String manifestText = new String(stripBom(entries.get("下载清单.txt")), StandardCharsets.UTF_8);
        assertTrue(manifestText.contains("TSH001000000002"), "无附件票号应在清单提示：\n" + manifestText);
    }

    @Test
    void zipOver50Tickets_isRejectedBeforeStreaming() {
        token = login();
        StringBuilder url = new StringBuilder(baseUrl() + "/api/ticket/attachments/zip?");
        for (int i = 1; i <= 51; i++) {
            if (i > 1) {
                url.append('&');
            }
            url.append("ticketNos=NO-SUCH-").append(String.format("%03d", i));
        }
        ResponseEntity<String> resp = rest.exchange(url.toString(), HttpMethod.GET,
                new HttpEntity<>(authHeaders()), String.class);
        // 业务失败 HTTP 200 + success=false（开流前拦截，响应仍是 JSON，不是半截 ZIP）
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue(resp.getBody() != null && resp.getBody().contains("\"success\":false"),
                "超 50 份应被拒绝：" + resp.getBody());
        assertTrue(resp.getBody().contains("单包最多 50 份"), "应提示单包上限：" + resp.getBody());
        MediaType contentType = resp.getHeaders().getContentType();
        assertNotNull(contentType);
        assertTrue(contentType.isCompatibleWith(MediaType.APPLICATION_JSON),
                "拒绝时必须仍是 JSON，实际=" + contentType);
    }

    @Test
    void deleteAttachment_isLogical_thenGoneFromListAndDownload() {
        token = login();
        String ticketNo = "TSH001000000001"; // 样本中无附件的在售票
        long id = uploadSample(ticketNo, "待删除_" + ticketNo + ".txt", "稍后删除".getBytes(StandardCharsets.UTF_8));

        // 删除（带原因）
        HttpHeaders h = authHeaders();
        ResponseEntity<String> del = rest.exchange(
                baseUrl() + "/api/ticket/attachments/" + id + "?reason=客户要求删除",
                HttpMethod.DELETE, new HttpEntity<>(h), String.class);
        assertEquals(HttpStatus.OK, del.getStatusCode(), del.getBody());
        assertTrue(del.getBody().contains("\"success\":true"));

        // 列表不再返回（逻辑删除对查询不可见）
        ResponseEntity<String> listResp = rest.exchange(
                baseUrl() + "/api/ticket/tickets/" + ticketNo + "/attachments",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertTrue(listResp.getBody().contains("\"data\":[]"), "逻辑删除后列表应为空：" + listResp.getBody());

        // 单下载按不存在处理（HTTP 200 + success=false 的统一 JSON 口径）
        ResponseEntity<String> single = rest.exchange(
                baseUrl() + "/api/ticket/attachments/" + id + "/download",
                HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
        assertEquals(HttpStatus.OK, single.getStatusCode());
        assertTrue(single.getBody().contains("\"success\":false")
                        && single.getBody().contains("附件不存在或已删除"),
                "删除后下载应被拒：" + single.getBody());
    }

    private long uploadSample(String ticketNo, String fileName, byte[] content) {
        HttpHeaders headers = authHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        ResponseEntity<String> up = rest.postForEntity(
                baseUrl() + "/api/ticket/tickets/" + ticketNo + "/attachments",
                new HttpEntity<>(form, headers), String.class);
        assertEquals(HttpStatus.OK, up.getStatusCode(), "上传应成功：" + up.getBody());
        try {
            com.fasterxml.jackson.databind.JsonNode data =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(up.getBody()).path("data");
            return data.path("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** ZIP 中央目录条目：general-purpose 标志位 + 条目名原始字节（不解码、不假设字符集）。 */
    private static class CentralEntry {
        final int flagBits;
        final byte[] rawName;

        CentralEntry(int flagBits, byte[] rawName) {
            this.flagBits = flagBits;
            this.rawName = rawName;
        }

        String rawNameUtf8() {
            return new String(rawName, StandardCharsets.UTF_8);
        }
    }

    /**
     * 手工解析 ZIP 中央目录（PK\x01\x02），拿到标志位与条目名原始字节。
     * 不复用 {@link ZipInputStream}：它会按构造字符集/标志位替我们解码，掩盖"字节与 EFS
     * 是否真的写对"，而这正是本回归要锁死的东西。
     */
    private List<CentralEntry> readCentralDirectory(byte[] zip) {
        int eocd = -1;
        for (int i = zip.length - 22; i >= 0; i--) {
            if (zip[i] == 0x50 && zip[i + 1] == 0x4b
                    && zip[i + 2] == 0x05 && zip[i + 3] == 0x06) {
                eocd = i;
                break;
            }
        }
        assertTrue(eocd >= 0, "响应体应为合法 ZIP（应能定位 EOCD）");
        int centralOffset = readUInt16(zip, eocd + 16) | (readUInt16(zip, eocd + 18) << 16);

        List<CentralEntry> entries = new ArrayList<>();
        int p = centralOffset;
        while (p + 4 <= zip.length
                && zip[p] == 0x50 && zip[p + 1] == 0x4b
                && zip[p + 2] == 0x01 && zip[p + 3] == 0x02) {
            int flagBits = readUInt16(zip, p + 8);
            int nameLen = readUInt16(zip, p + 28);
            int extraLen = readUInt16(zip, p + 30);
            int commentLen = readUInt16(zip, p + 32);
            byte[] rawName = new byte[nameLen];
            System.arraycopy(zip, p + 46, rawName, 0, nameLen);
            entries.add(new CentralEntry(flagBits, rawName));
            p += 46 + nameLen + extraLen + commentLen;
        }
        assertTrue(!entries.isEmpty(), "中央目录至少应有一个条目");
        return entries;
    }

    private int readUInt16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private Map<String, byte[]> readZip(byte[] body) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(body), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zin.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                entries.put(entry.getName(), out.toByteArray());
            }
        }
        return entries;
    }

    private String urlEncodeUtf8(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] stripBom(byte[] content) {        if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            byte[] stripped = new byte[content.length - 3];
            System.arraycopy(content, 3, stripped, 0, stripped.length);
            return stripped;
        }
        return content;
    }
}
