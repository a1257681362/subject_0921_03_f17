package com.evops.ticket.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 报文归档详情：含收发原文，按票号回查用。 */
@Data
public class AgentMessageDetailVO {
    private Long id;
    private String messageId;
    private String direction;
    private String messageType;
    private String ticketNo;
    private String orderNo;
    private String sessionCode;
    private String correlationId;
    private String endpoint;
    private Integer httpStatus;
    private String payload;
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
