package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 售票单（主表）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_sale_order")
public class SaleOrder extends TicketBaseEntity {
    private String orderNo;
    private Long sessionId;
    private Integer ticketCount;
    private BigDecimal totalAmount;
    /** ISSUED / VOIDED */
    private String status;
}
