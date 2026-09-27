package com.evops.ticket.notify.web;

import com.evops.auth.security.AuthContext;
import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.notify.dto.InboxMessageVO;
import com.evops.ticket.notify.service.InboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 场务站内信箱（前缀 /api/ticket/notify/inbox，需后台访问令牌）。
 * 场务登录后只能查到本人工号下的检票通知，支持标记已读与未读计数。
 */
@RestController
@RequestMapping("/api/ticket/notify/inbox")
@RequirePermission("notify:inbox:view")
public class InboxController {

    private final InboxService inboxService;

    public InboxController(InboxService inboxService) {
        this.inboxService = inboxService;
    }

    /** 我的站内信分页：readFlag 0 未读 / 1 已读 / 不传全部。 */
    @GetMapping("/messages")
    public ApiResponse<PageVO<InboxMessageVO>> pageMine(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Integer readFlag) {
        return ApiResponse.ok(inboxService.pageMine(currentStaffNo(), page, size, readFlag));
    }

    /** 未读数（场务端角标）。 */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("staffNo", currentStaffNo());
        data.put("unread", inboxService.countUnread(currentStaffNo()));
        return ApiResponse.ok(data);
    }

    @GetMapping("/messages/{id}")
    public ApiResponse<InboxMessageVO> getMine(@PathVariable Long id) {
        return ApiResponse.ok(inboxService.getMine(currentStaffNo(), id));
    }

    /** 标记已读（幂等）。 */
    @PostMapping("/messages/{id}/read")
    public ApiResponse<Map<String, Object>> markRead(@PathVariable Long id) {
        inboxService.markRead(currentStaffNo(), id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("read", true);
        return ApiResponse.ok(data);
    }

    private String currentStaffNo() {
        AuthContext.CurrentUser user = AuthContext.get();
        if (user == null) {
            throw new IllegalStateException("当前请求无登录态");
        }
        return user.getUsername();
    }
}
