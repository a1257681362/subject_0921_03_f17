package com.evops.ticket.dto.ticket;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TicketVO {
    private Long id;
    private String ticketNo;
    private Long orderId;
    private Long sessionId;
    private Long seatId;
    private BigDecimal price;
    private String status;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private Integer version;
}
