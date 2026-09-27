package com.evops.ticket.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 报文归档列表项（不含 payload 原文，回查详情再取原文）。 */
@Data
public class AgentMessageVO {
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
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private LocalDateTime createTime;
}
