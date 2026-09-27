package com.evops.ticket.dto.order;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class OrderDetailVO {
    private Long orderId;
    private String orderNo;
    private Long sessionId;
    private String showName;
    private Integer ticketCount;
    private String status;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private Integer version;
    private List<OrderTicketVO> tickets;
}
