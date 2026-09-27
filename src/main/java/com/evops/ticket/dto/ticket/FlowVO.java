package com.evops.ticket.dto.ticket;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FlowVO {
    private Long id;
    private String recordType;
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
