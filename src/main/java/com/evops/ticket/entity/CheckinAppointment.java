package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 检票排期预约。按半开区间 [startTime, endTime) 存储：
 * 同一座位 ACTIVE 区间重叠、或首尾间隔不足 20 分钟即冲突；
 * 取消置 CANCELLED 即释放（历史行保留），座位可再次预约。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_checkin_appointment")
public class CheckinAppointment extends TicketBaseEntity {
    private String appointmentNo;
    private Long seatId;
    private String visitorName;
    private String contact;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** ACTIVE / CANCELLED */
    private String status;
    private String cancelReason;
}
