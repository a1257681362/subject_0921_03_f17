package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 每场次票号顺序号计数行。
 */
@Data
@TableName("t_ticket_no_alloc")
public class TicketNoAlloc {
    @TableId(type = IdType.INPUT)
    private Long sessionId;
    private Long curVal;
    private LocalDateTime updateTime;
}
