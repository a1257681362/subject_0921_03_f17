package com.evops.ticket.dto.ticket;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TicketDetailVO {
    private Long id;
    private String ticketNo;
    private Long orderId;
    private String orderNo;
    private Long sessionId;
    private String showName;
    private String sessionCode;
    private LocalDateTime showTime;
    private Long seatId;
    private String venue;
    private String area;
    private String rowNo;
    private String seatNo;
    private BigDecimal price;
    private String status;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private Integer version;
    /** 追溯链：ISSUE 起按时间排列的全部流水 */
    private List<FlowVO> flows;
}
