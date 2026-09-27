package com.evops.ticket.attachment.web;

import com.evops.common.ApiResponse;
import com.evops.common.web.RequestContext;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.attachment.dto.AttachmentVO;
import com.evops.ticket.attachment.service.TicketAttachmentService;
import com.evops.ticket.attachment.service.TicketAttachmentStorage;
import com.evops.ticket.attachment.service.TicketZipStreamer;
import com.evops.ticket.entity.TicketAttachment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 演出票附件（客服支持）：上传登记、按票号列表、单个下载、整批 ZIP 下载。
 * 全部端点位于 /api/ticket/** 下，由 JwtAuthInterceptor 统一鉴权（无访问令牌 401）。
 *
 * 下载不使用 StreamingResponseBody：异步再分派会再次穿过 Shiro 过滤器链，
 * Shiro 未在分派线程绑定 SecurityManager 会报错；改为请求线程内直接写响应输出流，
 * 8KB 分块拷贝，整包同样不进内存，且避开异步分派。
 */
@RestController
@RequestMapping("/api/ticket")
public class TicketAttachmentController {

    private static final String ZIP_CONTENT_TYPE = "application/zip";
    private static final int BUFFER_SIZE = 8 * 1024;
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TicketAttachmentService attachmentService;
    private final TicketAttachmentStorage storage;
    private final TicketZipStreamer zipStreamer;

    public TicketAttachmentController(TicketAttachmentService attachmentService,
                                      TicketAttachmentStorage storage,
                                      TicketZipStreamer zipStreamer) {
        this.attachmentService = attachmentService;
        this.storage = storage;
        this.zipStreamer = zipStreamer;
    }

    /** 为指定票号上传一个附件（multipart 字段名 file）。 */
    @PostMapping(value = "/tickets/{ticketNo}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission("ticket:attachment:upload")
    public ApiResponse<AttachmentVO> upload(@PathVariable String ticketNo,
                                            @RequestParam("file") MultipartFile file,
                                            @RequestParam(required = false) String remark) {
        return ApiResponse.ok(attachmentService.upload(ticketNo, file, remark));
    }

    /** 按票号列出全部有效附件。 */
    @GetMapping("/tickets/{ticketNo}/attachments")
    @RequirePermission("ticket:attachment:download")
    public ApiResponse<List<AttachmentVO>> list(@PathVariable String ticketNo) {
        return ApiResponse.ok(attachmentService.list(ticketNo));
    }

    /** 单个附件下载：响应头带 UTF-8 编码的原始（中文）文件名，分块流式写出。 */
    @GetMapping("/attachments/{id}/download")
    @RequirePermission("ticket:attachment:download")
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        // 元数据/物理文件缺失在写响应前抛出，走全局异常处理回 JSON
        TicketAttachment attachment = attachmentService.requireAttachment(id);
        if (!storage.exists(attachment.getStorageName())) {
            throw new IllegalArgumentException("附件物理文件缺失，请联系运维核查存储目录：" + attachment.getFileName());
        }

        response.setContentType(attachment.getContentType() != null
                ? attachment.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE);
        if (attachment.getFileSize() != null && attachment.getFileSize() > 0) {
            response.setContentLengthLong(attachment.getFileSize());
        }
        setDisposition(response, attachment.getFileName());
        copy(storage.open(attachment.getStorageName()), response.getOutputStream());
    }

    /**
     * 整批 ZIP 下载：ticketNos 重复传入，如 ?ticketNos=T001&amp;ticketNos=T002；
     * 单包最多 50 份（应用层开流前拦截）；流式生成不占内存，包内带《下载清单.txt》。
     */
    @GetMapping("/attachments/zip")
    @RequirePermission("ticket:attachment:download")
    public void downloadZip(@RequestParam("ticketNos") List<String> ticketNos,
                            HttpServletResponse response) throws IOException {
        // 超限/无附件等可预判错误在此抛出，响应尚未提交，全局处理器回 JSON success=false
        TicketAttachmentService.ZipSelection selection = attachmentService.resolveForZip(ticketNos);
        String operator = RequestContext.operator();
        String requestId = RequestContext.requestId();

        response.setContentType(ZIP_CONTENT_TYPE);
        setDisposition(response, "演出票附件_" + LocalDateTime.now().format(FILE_TS) + ".zip");
        // ZIP 长度无法预知，由容器按 chunked/连接关闭交付
        zipStreamer.stream(selection, response.getOutputStream(), operator, requestId);
    }

    /** 删除附件（逻辑删除，保留元数据追溯；物理文件由运维按留存策略清理）。 */
    @DeleteMapping("/attachments/{id}")
    @RequirePermission("ticket:attachment:delete")
    public ApiResponse<Object> delete(@PathVariable Long id,
                                      @RequestParam(required = false) String reason) {
        attachmentService.delete(id, reason);
        return ApiResponse.ok(null);
    }

    private void copy(InputStream in, OutputStream out) {
        try (InputStream closeable = in) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = closeable.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            out.flush();
        } catch (IOException e) {
            // 响应可能已部分写出，客户端按不完整包处理、重试即可
            throw new UncheckedIOException("附件下载中断", e);
        }
    }

    /**
     * Content-Disposition：filename 给 ASCII 兜底，filename* 给 UTF-8 原文，
     * 保证浏览器保存时中文名不乱码（RFC 5987）。
     */
    private void setDisposition(HttpServletResponse response, String rawFileName) {
        String encoded;
        try {
            encoded = URLEncoder.encode(rawFileName, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 编码不可用", e);
        }
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + asciiFallback(rawFileName) + "\"; filename*=UTF-8''" + encoded);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    private String asciiFallback(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String fallback = sb.toString().replace("\"", "_");
        return fallback.isEmpty() ? "download" : fallback;
    }
}
