package com.evops.ticket.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 逐通道发送记录：一条 = 某事件经某通道发给某收件人的一次发送。
 * 渲染后标题/正文/变量集在此快照——模板日后只改版本，已发内容原样可查。
 */
@TableName("t_notify_record")
public class NotifyRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dispatchId;
    private String eventCode;
    /** INBOX / EMAIL */
    private String channel;
    private Long templateId;
    /** 发送时模板版本快照 */
    private Integer templateVersion;
    private Long recipientId;
    private String recipientName;
    /** INBOX 为工号，EMAIL 为邮箱地址 */
    private String recipientAddress;
    private String ticketNo;
    private String orderNo;
    private String sessionCode;
    /** 渲染后标题快照 */
    private String titleRendered;
    /** 渲染后正文快照 */
    private String contentRendered;
    /** 渲染变量集 JSON 快照 */
    private String varsSnapshot;
    /** 对端回执消息号（邮件网关返回；站内信为站内消息 id） */
    private String messageId;
    private Integer httpStatus;
    /** PENDING / SUCCESS / RETRYING / FAILED / SKIPPED */
    private String status;
    private Integer retryCount;
    private Integer maxRetry;
    private LocalDateTime nextRetryTime;
    private String lastError;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private LocalDateTime createTime;
    private LocalDateTime sentTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDispatchId() { return dispatchId; }
    public void setDispatchId(Long dispatchId) { this.dispatchId = dispatchId; }
    public String getEventCode() { return eventCode; }
    public void setEventCode(String eventCode) { this.eventCode = eventCode; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public Integer getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(Integer templateVersion) { this.templateVersion = templateVersion; }
    public Long getRecipientId() { return recipientId; }
    public void setRecipientId(Long recipientId) { this.recipientId = recipientId; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getRecipientAddress() { return recipientAddress; }
    public void setRecipientAddress(String recipientAddress) { this.recipientAddress = recipientAddress; }
    public String getTicketNo() { return ticketNo; }
    public void setTicketNo(String ticketNo) { this.ticketNo = ticketNo; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getSessionCode() { return sessionCode; }
    public void setSessionCode(String sessionCode) { this.sessionCode = sessionCode; }
    public String getTitleRendered() { return titleRendered; }
    public void setTitleRendered(String titleRendered) { this.titleRendered = titleRendered; }
    public String getContentRendered() { return contentRendered; }
    public void setContentRendered(String contentRendered) { this.contentRendered = contentRendered; }
    public String getVarsSnapshot() { return varsSnapshot; }
    public void setVarsSnapshot(String varsSnapshot) { this.varsSnapshot = varsSnapshot; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer httpStatus) { this.httpStatus = httpStatus; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Integer getMaxRetry() { return maxRetry; }
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }
    public LocalDateTime getNextRetryTime() { return nextRetryTime; }
    public void setNextRetryTime(LocalDateTime nextRetryTime) { this.nextRetryTime = nextRetryTime; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getSentTime() { return sentTime; }
    public void setSentTime(LocalDateTime sentTime) { this.sentTime = sentTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
