package com.evops.ticket.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 代理平台报文异常清单：解析失败 / 校验失败 / 业务处理失败统一登记，保留原文，可回查、可标记处理。
 */
@TableName("t_agent_parse_error")
public class AgentParseError {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** OUTBOUND / INBOUND */
    private String direction;

    /** PARSE_ERROR / VALIDATION_ERROR / BIZ_ERROR */
    private String errorType;

    private String messageId;
    private String ticketNo;
    private String messageType;

    /** 原始报文（坏 XML 也原样保留） */
    private String payload;

    private String errorDetail;

    /** OPEN / RESOLVED / IGNORED */
    private String status;

    private String resolveRemark;
    private String operator;
    private String requestId;
    private LocalDateTime createTime;
    private LocalDateTime resolveTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getTicketNo() { return ticketNo; }
    public void setTicketNo(String ticketNo) { this.ticketNo = ticketNo; }
    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String errorDetail) { this.errorDetail = errorDetail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResolveRemark() { return resolveRemark; }
    public void setResolveRemark(String resolveRemark) { this.resolveRemark = resolveRemark; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getResolveTime() { return resolveTime; }
    public void setResolveTime(LocalDateTime resolveTime) { this.resolveTime = resolveTime; }
}
