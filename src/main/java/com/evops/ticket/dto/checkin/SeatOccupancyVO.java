package com.evops.ticket.dto.checkin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 每座位占用视图：座位主数据 + 查询区间内的有效预约时间块。
 */
@Data
public class SeatOccupancyVO {
    private Long seatId;
    private String venue;
    private String area;
    private String rowNo;
    private String seatNo;

    /** 查询区间 [rangeStart, rangeEnd) */
    private LocalDateTime rangeStart;
    private LocalDateTime rangeEnd;

    /** 区间内的有效占用（按开始时间升序） */
    private List<AppointmentVO> appointments;
}
