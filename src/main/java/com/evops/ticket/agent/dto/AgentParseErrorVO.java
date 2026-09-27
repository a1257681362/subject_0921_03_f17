package com.evops.ticket.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 异常清单项（列表不含原文，详情/排查时按 id 取）。 */
@Data
public class AgentParseErrorVO {
    private Long id;
    private String direction;
    private String errorType;
    private String messageId;
    private String ticketNo;
    private String messageType;
    private String errorDetail;
    private String status;
    private String resolveRemark;
    private String operator;
    private String requestId;
    private LocalDateTime createTime;
    private LocalDateTime resolveTime;
}
