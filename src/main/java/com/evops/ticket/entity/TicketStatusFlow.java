package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 票状态流水（只追加）。ISSUE/CHECK 为正向，REFUND/VOID 为反向；
 * originRecordId 指向本票最初 ISSUE 流水，构成追溯链。
 */
@Data
@TableName("t_ticket_status_flow")
public class TicketStatusFlow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ticketId;
    private Long orderId;
    private Long sessionId;
    /** ISSUE / CHECK / REFUND / VOID */
    private String recordType;
    /** 0 正向 / 1 反向 */
    private Integer reverseFlag;
    private Long originRecordId;
    private String fromStatus;
    private String toStatus;
    private String reason;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private LocalDateTime createTime;
}
