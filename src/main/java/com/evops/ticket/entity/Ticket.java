package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 票明细（从表）。同一座位按场次独立售卖，历史票（已退/已作废）原样保留。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ticket")
public class Ticket extends TicketBaseEntity {
    private String ticketNo;
    private Long orderId;
    private Long sessionId;
    private Long seatId;
    private BigDecimal price;
    /** SOLD / CHECKED / REFUNDED / VOID */
    private String status;
}
