package com.evops.ticket.agent.web;

import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.agent.dto.AgentErrorResolveRequest;
import com.evops.ticket.agent.dto.AgentMessageDetailVO;
import com.evops.ticket.agent.dto.AgentMessageVO;
import com.evops.ticket.agent.dto.AgentParseErrorVO;
import com.evops.ticket.agent.service.AgentMessageAdminService;
import com.evops.ticket.agent.service.OutboundAgentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 代理对接管理端（前缀 /api/ticket/agent，需后台访问令牌）。
 * 报文按票号归档回查、出站失败手动重发、按票补推、异常清单检索与标记处理。
 */
@RestController
@RequestMapping("/api/ticket/agent")
@RequirePermission("agent:manage")
public class AgentAdminController {

    private final AgentMessageAdminService adminService;
    private final OutboundAgentService outboundService;

    public AgentAdminController(AgentMessageAdminService adminService,
                                OutboundAgentService outboundService) {
        this.adminService = adminService;
        this.outboundService = outboundService;
    }

    // ==================== 报文归档 ====================

    /** 报文分页：direction / messageType / status / ticketNo 可选。 */
    @GetMapping("/messages")
    public ApiResponse<PageVO<AgentMessageVO>> pageMessages(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String messageType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ticketNo) {
        return ApiResponse.ok(adminService.pageMessages(page, size, direction, messageType, status, ticketNo));
    }

    /** 报文详情（含收发原文）。 */
    @GetMapping("/messages/{id}")
    public ApiResponse<AgentMessageDetailVO> getMessage(@PathVariable Long id) {
        return ApiResponse.ok(adminService.getMessage(id));
    }

    /** 按票号归档回查：该票全部收发报文原文，时间正序。 */
    @GetMapping("/tickets/{ticketNo}/messages")
    public ApiResponse<List<AgentMessageDetailVO>> listByTicket(@PathVariable String ticketNo) {
        return ApiResponse.ok(adminService.listByTicketNo(ticketNo));
    }

    /** 手动重发一条出站失败/待发报文（沿用原 messageId，平台侧幂等）。 */
    @PostMapping("/messages/{id}/retry")
    public ApiResponse<AgentMessageDetailVO> retry(@PathVariable Long id) {
        return ApiResponse.ok(adminService.toDetailOrNull(outboundService.manualRetry(id)));
    }

    /** 按票补推：依票当前状态重新生成一条报文投递（新 messageId）。 */
    @PostMapping("/tickets/{ticketNo}/push")
    public ApiResponse<AgentMessageDetailVO> pushTicket(@PathVariable String ticketNo) {
        return ApiResponse.ok(adminService.toDetailOrNull(outboundService.resendTicket(ticketNo)));
    }

    // ==================== 异常清单 ====================

    /** 异常清单分页：默认只看 OPEN；可按 status/errorType/ticketNo 过滤。 */
    @GetMapping("/errors")
    public ApiResponse<PageVO<AgentParseErrorVO>> pageErrors(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String errorType,
            @RequestParam(required = false) String ticketNo) {
        return ApiResponse.ok(adminService.pageErrors(page, size, status, errorType, ticketNo));
    }

    /** 异常详情。 */
    @GetMapping("/errors/{id}")
    public ApiResponse<AgentParseErrorVO> getError(@PathVariable Long id) {
        AgentParseErrorVO vo = new AgentParseErrorVO();
        org.springframework.beans.BeanUtils.copyProperties(adminService.getErrorEntity(id), vo);
        return ApiResponse.ok(vo);
    }

    /** 异常关联的原始报文（坏 XML 原样回查）。 */
    @GetMapping(value = "/errors/{id}/payload", produces = "application/xml;charset=UTF-8")
    public String errorPayload(@PathVariable Long id) {
        return adminService.getErrorPayload(id);
    }

    /** 标记异常已处理/已忽略。 */
    @PostMapping("/errors/{id}/resolve")
    public ApiResponse<Object> resolveError(@PathVariable Long id,
                                            @RequestBody(required = false) AgentErrorResolveRequest request) {
        String status = request == null ? null : request.getStatus();
        String remark = request == null ? null : request.getRemark();
        adminService.resolveError(id, status, remark);
        return ApiResponse.ok(java.util.Collections.singletonMap("resolved", true));
    }
}
