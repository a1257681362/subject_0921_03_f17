package com.evops.ticket.dto.order;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderTicketVO {
    private Long id;
    private String ticketNo;
    private Long seatId;
    private BigDecimal price;
    private String status;
}
