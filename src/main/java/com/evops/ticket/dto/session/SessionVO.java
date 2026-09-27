package com.evops.ticket.dto.session;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SessionVO {
    private Long id;
    private String showName;
    private String sessionCode;
    private String venue;
    private LocalDateTime showTime;
    private String status;
    private String remark;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private Integer version;
    private LocalDateTime createTime;
}
