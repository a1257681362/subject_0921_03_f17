package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 演出场次。sessionCode 为票号组成短码。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_show_session")
public class ShowSession extends TicketBaseEntity {
    private String showName;
    private String sessionCode;
    private String venue;
    private LocalDateTime showTime;
    /** OPEN / CLOSED */
    private String status;
    private String remark;
}
