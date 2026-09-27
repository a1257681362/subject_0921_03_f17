package com.evops.ticket.dto.checkin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 冲突详情：随 success=false 返回，含撞上的有效预约与推荐空档，场务可直接改约。
 */
@Data
public class AppointmentConflictVO {
    private Long seatId;
    private String venue;
    private String area;
    private String rowNo;
    private String seatNo;
    private LocalDateTime requestedStart;
    private LocalDateTime requestedEnd;

    /** 缓冲间隔（分钟）：相邻预约首尾不足该值即冲突 */
    private int bufferMinutes;

    /** 撞上的有效预约（区间重叠或间隔不足） */
    private List<AppointmentVO> conflicts;

    /** 以请求区间为窗口、能容纳请求时长的推荐空档（按开始时间升序） */
    private List<FreeSlotVO> suggestedSlots;
}
