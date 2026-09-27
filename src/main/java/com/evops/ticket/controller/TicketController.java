package com.evops.ticket.controller;

import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.dto.ticket.TicketActionRequest;
import com.evops.ticket.dto.ticket.TicketDetailVO;
import com.evops.ticket.dto.ticket.TicketVO;
import com.evops.ticket.service.IdempotentResult;
import com.evops.ticket.service.IdempotentService;
import com.evops.ticket.service.TicketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ticket/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final IdempotentService idempotentService;

    public TicketController(TicketService ticketService, IdempotentService idempotentService) {
        this.ticketService = ticketService;
        this.idempotentService = idempotentService;
    }

    /** 按票号精确检索（含场次、座位与追溯流水）。 */
    @GetMapping("/{ticketNo}")
    @RequirePermission("ticket:ticket:view")
    public ApiResponse<TicketDetailVO> getByTicketNo(@PathVariable String ticketNo) {
        return ApiResponse.ok(ticketService.getByTicketNo(ticketNo));
    }

    /** 分页检索：票号 / 场次 / 状态 / 订单号。 */
    @GetMapping
    @RequirePermission("ticket:ticket:view")
    public ApiResponse<PageVO<TicketVO>> search(@RequestParam(defaultValue = "1") long page,
                                                @RequestParam(defaultValue = "20") long size,
                                                @RequestParam(required = false) String ticketNo,
                                                @RequestParam(required = false) Long sessionId,
                                                @RequestParam(required = false) String status,
                                                @RequestParam(required = false) String orderNo) {
        return ApiResponse.ok(ticketService.search(page, size, ticketNo, sessionId, status, orderNo));
    }

    /** 检票：SOLD -> CHECKED。 */
    @PostMapping("/{ticketNo}/check")
    @RequirePermission("ticket:ticket:check")
    public ApiResponse<Object> check(@PathVariable String ticketNo,
                                     @RequestBody(required = false) TicketActionRequest request) {
        TicketActionRequest body = request != null ? request : new TicketActionRequest();
        Object data = idempotentService.execute(idempotentService.hash(body), () ->
                IdempotentResult.of("TICKET:" + ticketNo + ":CHECK", ticketService.check(ticketNo, body)));
        return ApiResponse.ok(data);
    }

    /** 退票：SOLD -> REFUNDED，释放座次，写反向流水。 */
    @PostMapping("/{ticketNo}/refund")
    @RequirePermission("ticket:ticket:refund")
    public ApiResponse<Object> refund(@PathVariable String ticketNo,
                                      @RequestBody(required = false) TicketActionRequest request) {
        TicketActionRequest body = request != null ? request : new TicketActionRequest();
        Object data = idempotentService.execute(idempotentService.hash(body), () ->
                IdempotentResult.of("TICKET:" + ticketNo + ":REFUND", ticketService.refund(ticketNo, body)));
        return ApiResponse.ok(data);
    }

    /** 作废：SOLD/CHECKED -> VOID，释放座次，写反向流水。 */
    @PostMapping("/{ticketNo}/void")
    @RequirePermission("ticket:ticket:void")
    public ApiResponse<Object> voidTicket(@PathVariable String ticketNo,
                                          @RequestBody(required = false) TicketActionRequest request) {
        TicketActionRequest body = request != null ? request : new TicketActionRequest();
        Object data = idempotentService.execute(idempotentService.hash(body), () ->
                IdempotentResult.of("TICKET:" + ticketNo + ":VOID", ticketService.voidTicket(ticketNo, body)));
        return ApiResponse.ok(data);
    }
}
