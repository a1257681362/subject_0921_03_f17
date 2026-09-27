package com.evops.ticket.notify.web;

import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.notify.dto.NotifyRecordVO;
import com.evops.ticket.notify.dto.NotifyRecipientVO;
import com.evops.ticket.notify.dto.NotifyTemplateVO;
import com.evops.ticket.notify.dto.RecipientUpsertRequest;
import com.evops.ticket.notify.dto.TemplateUpsertRequest;
import com.evops.ticket.notify.dto.stats.NotifyStatsVO;
import com.evops.ticket.notify.service.NotifyAdminService;
import com.evops.ticket.notify.service.NotifySendService;
import com.evops.ticket.notify.service.NotifyStatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;

/**
 * 检票通知管理端（前缀 /api/ticket/notify，需后台访问令牌）。
 * 模板/收件人维护、发送记录与批次回查、失败手动重发、发送结果统计。
 */
@RestController
@RequestMapping("/api/ticket/notify")
@RequirePermission("notify:manage")
public class NotifyAdminController {

    private final NotifyAdminService adminService;
    private final NotifySendService sendService;
    private final NotifyStatsService statsService;

    public NotifyAdminController(NotifyAdminService adminService, NotifySendService sendService,
                                 NotifyStatsService statsService) {
        this.adminService = adminService;
        this.sendService = sendService;
        this.statsService = statsService;
    }

    // ==================== 模板 ====================

    @GetMapping("/templates")
    public ApiResponse<PageVO<NotifyTemplateVO>> pageTemplates(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String eventCode,
            @RequestParam(required = false) String channel) {
        return ApiResponse.ok(adminService.pageTemplates(page, size, eventCode, channel));
    }

    @PostMapping("/templates")
    public ApiResponse<NotifyTemplateVO> createTemplate(@Valid @RequestBody TemplateUpsertRequest req) {
        return ApiResponse.ok(adminService.createTemplate(req));
    }

    /** 修改模板：文案变化自动升版本；已发内容不受影响（发送记录已快照）。 */
    @PutMapping("/templates/{id}")
    public ApiResponse<NotifyTemplateVO> updateTemplate(@PathVariable Long id,
                                                        @Valid @RequestBody TemplateUpsertRequest req) {
        return ApiResponse.ok(adminService.updateTemplate(id, req));
    }

    // ==================== 收件人 ====================

    @GetMapping("/recipients")
    public ApiResponse<PageVO<NotifyRecipientVO>> pageRecipients(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(adminService.pageRecipients(page, size, roleCode, keyword));
    }

    @PostMapping("/recipients")
    public ApiResponse<NotifyRecipientVO> createRecipient(@Valid @RequestBody RecipientUpsertRequest req) {
        return ApiResponse.ok(adminService.createRecipient(req));
    }

    @PutMapping("/recipients/{id}")
    public ApiResponse<NotifyRecipientVO> updateRecipient(@PathVariable Long id,
                                                          @Valid @RequestBody RecipientUpsertRequest req) {
        return ApiResponse.ok(adminService.updateRecipient(id, req));
    }

    // ==================== 发送记录 ====================

    /** 发送记录分页：eventCode/channel/status/ticketNo/dispatchId 可选过滤。 */
    @GetMapping("/records")
    public ApiResponse<PageVO<NotifyRecordVO>> pageRecords(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String eventCode,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ticketNo,
            @RequestParam(required = false) Long dispatchId) {
        return ApiResponse.ok(adminService.pageRecords(page, size, eventCode, channel, status, ticketNo, dispatchId));
    }

    @GetMapping("/records/{id}")
    public ApiResponse<NotifyRecordVO> getRecord(@PathVariable Long id) {
        return ApiResponse.ok(adminService.getRecord(id));
    }

    /** 失败记录手动重发（RETRYING/FAILED 立即再投一次）。 */
    @PostMapping("/records/{id}/retry")
    public ApiResponse<NotifyRecordVO> retryRecord(@PathVariable Long id) {
        return ApiResponse.ok(toVO(sendService.manualRetry(id)));
    }

    // ==================== 统计 ====================

    /**
     * 发送结果统计：批次维度（全部成功/部分/全失败）+ 通道维度计数。
     * 时间按发送记录创建时间（可选 ISO 日期时间，如 2026-09-23T00:00:00）。
     */
    @GetMapping("/stats")
    public ApiResponse<NotifyStatsVO> stats(
            @RequestParam(required = false) String eventCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(statsService.stats(eventCode, from, to));
    }

    private NotifyRecordVO toVO(com.evops.ticket.notify.entity.NotifyRecord r) {
        NotifyRecordVO vo = new NotifyRecordVO();
        org.springframework.beans.BeanUtils.copyProperties(r, vo);
        return vo;
    }
}
