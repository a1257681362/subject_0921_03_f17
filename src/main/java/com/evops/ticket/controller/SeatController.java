package com.evops.ticket.controller;

import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.dto.seat.SeatCreateRequest;
import com.evops.ticket.dto.seat.SeatVO;
import com.evops.ticket.service.IdempotentResult;
import com.evops.ticket.service.IdempotentService;
import com.evops.ticket.service.SeatService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Collections;

@RestController
@RequestMapping("/api/ticket/seats")
public class SeatController {

    private final SeatService seatService;
    private final IdempotentService idempotentService;

    public SeatController(SeatService seatService, IdempotentService idempotentService) {
        this.seatService = seatService;
        this.idempotentService = idempotentService;
    }

    @PostMapping
    @RequirePermission("ticket:seat:create")
    public ApiResponse<Object> create(@Valid @RequestBody SeatCreateRequest request) {
        Object data = idempotentService.execute(idempotentService.hash(request), () -> {
            SeatVO vo = seatService.create(request);
            return IdempotentResult.of("SEAT:" + vo.getId(), vo);
        });
        return ApiResponse.ok(data);
    }

    @GetMapping("/{id}")
    @RequirePermission("ticket:seat:view")
    public ApiResponse<SeatVO> get(@PathVariable Long id) {
        return ApiResponse.ok(seatService.get(id));
    }

    @GetMapping
    @RequirePermission("ticket:seat:view")
    public ApiResponse<PageVO<SeatVO>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "20") long size,
                                            @RequestParam(required = false) String venue,
                                            @RequestParam(required = false) String area) {
        return ApiResponse.ok(seatService.page(page, size, venue, area));
    }

    /** 删除只打标记，走幂等；重复 requestId 返回首次结果。 */
    @DeleteMapping("/{id}")
    @RequirePermission("ticket:seat:delete")
    public ApiResponse<Object> delete(@PathVariable Long id,
                                      @RequestBody(required = false) String body) {
        Object data = idempotentService.execute(idempotentService.hash(body), () -> {
            seatService.delete(id);
            return IdempotentResult.of("SEAT:" + id + ":DELETE", Collections.singletonMap("deleted", true));
        });
        return ApiResponse.ok(data);
    }
}
