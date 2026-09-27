package com.evops.ticket.controller;

import com.evops.common.ApiResponse;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.dto.order.OrderDetailVO;
import com.evops.ticket.dto.order.OrderVoidRequest;
import com.evops.ticket.dto.order.SaleCreateRequest;
import com.evops.ticket.service.IdempotentResult;
import com.evops.ticket.service.IdempotentService;
import com.evops.ticket.service.SaleOrderService;
import com.evops.ticket.service.TicketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Collections;

@RestController
@RequestMapping("/api/ticket/orders")
public class SaleOrderController {

    private final SaleOrderService orderService;
    private final TicketService ticketService;
    private final IdempotentService idempotentService;

    public SaleOrderController(SaleOrderService orderService, TicketService ticketService,
                               IdempotentService idempotentService) {
        this.orderService = orderService;
        this.ticketService = ticketService;
        this.idempotentService = idempotentService;
    }

    /** 售票单 + 票明细建档：主从同事务；双击同 requestId 只生成一条记录。 */
    @PostMapping
    @RequirePermission("ticket:order:create")
    public ApiResponse<Object> create(@Valid @RequestBody SaleCreateRequest request) {
        Object data = idempotentService.execute(idempotentService.hash(request), () -> {
            OrderDetailVO vo = orderService.create(request);
            return IdempotentResult.of("ORDER:" + vo.getOrderId(), vo);
        });
        return ApiResponse.ok(data);
    }

    @GetMapping("/{id}")
    @RequirePermission("ticket:order:view")
    public ApiResponse<OrderDetailVO> get(@PathVariable Long id) {
        return ApiResponse.ok(orderService.getDetail(id));
    }

    /** 整单作废：票逐张走反向记录，任一张不可作废整体回滚。 */
    @PostMapping("/{id}/void")
    @RequirePermission("ticket:order:void")
    public ApiResponse<Object> voidOrder(@PathVariable Long id,
                                         @RequestBody(required = false) OrderVoidRequest request) {
        OrderVoidRequest body = request != null ? request : new OrderVoidRequest();
        Object data = idempotentService.execute(idempotentService.hash(body), () -> {
            ticketService.voidOrder(id, body);
            return IdempotentResult.of("ORDER:" + id + ":VOID", Collections.singletonMap("voided", true));
        });
        return ApiResponse.ok(data);
    }
}
