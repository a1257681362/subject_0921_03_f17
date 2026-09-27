package com.evops.ticket.dto.checkin;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 可预约空档：落在该区间内的任意 [开始, 开始+时长) 都与既有预约满足 20 分钟间隔。
 */
@Data
public class FreeSlotVO {
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    public FreeSlotVO() {
    }

    public FreeSlotVO(LocalDateTime startTime, LocalDateTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }
}
