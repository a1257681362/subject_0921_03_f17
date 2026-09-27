package com.evops.ticket.dto.seat;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SeatVO {
    private Long id;
    private String venue;
    private String area;
    private String rowNo;
    private String seatNo;
    private String remark;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private Integer version;
    private LocalDateTime createTime;
}
