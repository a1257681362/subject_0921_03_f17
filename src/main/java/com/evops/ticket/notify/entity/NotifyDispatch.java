package com.evops.ticket.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 通知批次：一次业务事件触发一条，汇总该事件双通道发送结果。
 * (eventCode, ticketId) 唯一，事件重复消费不会重复通知。
 */
@TableName("t_notify_dispatch")
public class NotifyDispatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventCode;
    private Long ticketId;
    private String ticketNo;
    private String orderNo;
    private String sessionCode;
    /** SUCCESS / PARTIAL / FAILED */
    private String status;
    private Integer totalChannels;
    private Integer successChannels;
    private Integer failChannels;
    private String triggerOperator;
    private String requestId;
    private LocalDateTime eventTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventCode() { return eventCode; }
    public void setEventCode(String eventCode) { this.eventCode = eventCode; }
    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public String getTicketNo() { return ticketNo; }
    public void setTicketNo(String ticketNo) { this.ticketNo = ticketNo; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getSessionCode() { return sessionCode; }
    public void setSessionCode(String sessionCode) { this.sessionCode = sessionCode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getTotalChannels() { return totalChannels; }
    public void setTotalChannels(Integer totalChannels) { this.totalChannels = totalChannels; }
    public Integer getSuccessChannels() { return successChannels; }
    public void setSuccessChannels(Integer successChannels) { this.successChannels = successChannels; }
    public Integer getFailChannels() { return failChannels; }
    public void setFailChannels(Integer failChannels) { this.failChannels = failChannels; }
    public String getTriggerOperator() { return triggerOperator; }
    public void setTriggerOperator(String triggerOperator) { this.triggerOperator = triggerOperator; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
