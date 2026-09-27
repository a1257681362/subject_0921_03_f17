package com.evops.ticket.sensitive.web;

import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.sensitive.dto.SensitiveHitLogVO;
import com.evops.ticket.sensitive.dto.SensitivePreviewRequest;
import com.evops.ticket.sensitive.dto.SensitiveWordUpsertRequest;
import com.evops.ticket.sensitive.dto.SensitiveWordVO;
import com.evops.ticket.sensitive.service.SensitiveAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词管理端（前缀 /api/ticket/sensitive，仅 ADMIN 岗位持有相关权限码）。
 * 词库维护后立即热刷新 DFA；命中留痕分页审计；上线前可用试扫验证效果。
 */
@RestController
@RequestMapping("/api/ticket/sensitive")
public class SensitiveAdminController {

    private final SensitiveAdminService adminService;

    public SensitiveAdminController(SensitiveAdminService adminService) {
        this.adminService = adminService;
    }

    // ==================== 词库 ====================

    /** 词库列表（可按级别/状态/词文本过滤）。 */
    @GetMapping("/words")
    @RequirePermission("sensitive:word:view")
    public ApiResponse<List<SensitiveWordVO>> listWords(@RequestParam(required = false) String level,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(adminService.listWords(level, status, keyword));
    }

    /** 新增词条（同形拒绝，提交后立即生效）。 */
    @PostMapping("/words")
    @RequirePermission("sensitive:word:manage")
    public ApiResponse<SensitiveWordVO> addWord(@Valid @RequestBody SensitiveWordUpsertRequest request) {
        return ApiResponse.ok(adminService.addWord(request));
    }

    /** 停用词条（留档，不再参与扫描）。 */
    @PostMapping("/words/{id}/disable")
    @RequirePermission("sensitive:word:manage")
    public ApiResponse<Object> disable(@PathVariable Long id) {
        adminService.disableWord(id);
        return ApiResponse.ok(null);
    }

    /** 重新启用词条。 */
    @PostMapping("/words/{id}/enable")
    @RequirePermission("sensitive:word:manage")
    public ApiResponse<Object> enable(@PathVariable Long id) {
        adminService.enableWord(id);
        return ApiResponse.ok(null);
    }

    /** 手动重刷 DFA + 当前生效词数（运维应急入口）。 */
    @PostMapping("/reload")
    @RequirePermission("sensitive:word:manage")
    public ApiResponse<Map<String, Object>> reload() {
        int count = adminService.reload();
        Map<String, Object> data = new HashMap<>();
        data.put("wordCount", count);
        return ApiResponse.ok(data);
    }

    // ==================== 命中留痕 ====================

    /** 命中留痕分页：可按场景/级别/处置/关键词（词、业务号、原文）过滤。 */
    @GetMapping("/hits")
    @RequirePermission("sensitive:hitlog:view")
    public ApiResponse<PageVO<SensitiveHitLogVO>> pageHits(@RequestParam(defaultValue = "1") long page,
                                                           @RequestParam(defaultValue = "20") long size,
                                                           @RequestParam(required = false) String scene,
                                                           @RequestParam(required = false) String level,
                                                           @RequestParam(required = false) String action,
                                                           @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(adminService.pageHits(page, size, scene, level, action, keyword));
    }

    // ==================== 试扫 ====================

    /** 试扫：不落业务、不留痕，返回命中词/级别/原文片段/码点位置，词库上线前验证用。 */
    @PostMapping("/preview")
    @RequirePermission("sensitive:word:manage")
    public ApiResponse<Object> preview(@Valid @RequestBody SensitivePreviewRequest request) {
        Map<String, Object> data = new HashMap<>();
        data.put("hits", adminService.preview(request.getText()));
        return ApiResponse.ok(data);
    }
}
