package com.evops.ticket.mask.web;

import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.mask.dto.MaskRulePublishRequest;
import com.evops.ticket.mask.dto.MaskRuleVersionVO;
import com.evops.ticket.mask.dto.SensitiveViewLogVO;
import com.evops.ticket.mask.service.MaskAdminService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检票数据脱敏管理端（前缀 /api/ticket/mask，需访问令牌）。
 *
 * <ul>
 *   <li>规则版本：查询历史版本/当前版本、发布新版本（旧版 SUPERSEDED 留档，发布即生效）；</li>
 *   <li>明文查看留痕：白名单角色每次看到完整值都可在此审计（谁/何时/何接口/哪条数据/哪个字段）。</li>
 * </ul>
 * 全部端点仅持有 mask:* 权限的岗位可用（种子仅 ADMIN），其它岗位由 RbacInterceptor 统一 403 并留痕。
 */
@RestController
@RequestMapping("/api/ticket/mask")
public class MaskAdminController {

    private final MaskAdminService adminService;

    public MaskAdminController(MaskAdminService adminService) {
        this.adminService = adminService;
    }

    // ==================== 规则版本 ====================

    /** 全部规则版本（新版本在前，含 SUPERSEDED 历史版本）。 */
    @GetMapping("/rules")
    @RequirePermission("mask:rule:view")
    public ApiResponse<List<MaskRuleVersionVO>> listRules() {
        return ApiResponse.ok(adminService.listVersions());
    }

    /** 当前唯一生效版本。 */
    @GetMapping("/rules/current")
    @RequirePermission("mask:rule:view")
    public ApiResponse<MaskRuleVersionVO> currentRule() {
        return ApiResponse.ok(adminService.currentVersion());
    }

    /**
     * 发布新版本：字段映射 + 白名单角色；旧版本置 SUPERSEDED 留档、新版本立即生效。
     */
    @PostMapping("/rules/publish")
    @RequirePermission("mask:rule:publish")
    public ApiResponse<MaskRuleVersionVO> publishRule(@Valid @RequestBody MaskRulePublishRequest request) {
        return ApiResponse.ok(adminService.publish(request));
    }

    // ==================== 明文查看留痕 ====================

    /** 明文查看留痕明细分页（按人/角色/业务数据/出口通道/时间过滤）。 */
    @GetMapping("/view-logs")
    @RequirePermission("mask:viewlog:view")
    public ApiResponse<PageVO<SensitiveViewLogVO>> pageViewLogs(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String bizId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(adminService.pageViewLogs(page, size, username, roleCode,
                bizType, bizId, channel, from, to));
    }
}
