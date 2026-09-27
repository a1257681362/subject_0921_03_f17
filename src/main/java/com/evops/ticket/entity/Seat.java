package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 座位主数据：场馆 / 区域 / 排 / 座号。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_seat")
public class Seat extends TicketBaseEntity {
    private String venue;
    private String area;
    private String rowNo;
    private String seatNo;
    private String remark;
}
