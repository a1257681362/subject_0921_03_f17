package com.evops.ticket.controller;

import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.dto.session.SessionCreateRequest;
import com.evops.ticket.dto.session.SessionVO;
import com.evops.ticket.service.IdempotentResult;
import com.evops.ticket.service.IdempotentService;
import com.evops.ticket.service.ShowSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/ticket/sessions")
public class ShowSessionController {

    private final ShowSessionService sessionService;
    private final IdempotentService idempotentService;

    public ShowSessionController(ShowSessionService sessionService, IdempotentService idempotentService) {
        this.sessionService = sessionService;
        this.idempotentService = idempotentService;
    }

    @PostMapping
    @RequirePermission("ticket:session:create")
    public ApiResponse<Object> create(@Valid @RequestBody SessionCreateRequest request) {
        Object data = idempotentService.execute(idempotentService.hash(request), () -> {
            SessionVO vo = sessionService.create(request);
            return IdempotentResult.of("SESSION:" + vo.getId(), vo);
        });
        return ApiResponse.ok(data);
    }

    @GetMapping("/{id}")
    @RequirePermission("ticket:session:view")
    public ApiResponse<SessionVO> get(@PathVariable Long id) {
        return ApiResponse.ok(sessionService.get(id));
    }

    @GetMapping
    @RequirePermission("ticket:session:view")
    public ApiResponse<PageVO<SessionVO>> page(@RequestParam(defaultValue = "1") long page,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String venue) {
        return ApiResponse.ok(sessionService.page(page, size, venue));
    }
}
