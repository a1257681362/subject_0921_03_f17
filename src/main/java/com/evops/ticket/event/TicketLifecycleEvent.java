package com.evops.ticket.event;

import com.evops.ticket.enums.FlowType;

/**
 * 票生命周期事件：出票 / 检票 / 退票 / 作废。
 * 在票状态变更所在事务内发布，由对接方在事务提交后（AFTER_COMMIT）消费；
 * 事务回滚则不触发，避免把未落库的状态变更通知给代理平台。
 * 快照操作人/请求号，避免提交后异步阶段 ThreadLocal 已清理。
 */
public class TicketLifecycleEvent {

    private final Long ticketId;
    private final FlowType flowType;
    private final String reason;
    private final String operator;
    private final String requestId;

    public TicketLifecycleEvent(Long ticketId, FlowType flowType, String reason,
                                String operator, String requestId) {
        this.ticketId = ticketId;
        this.flowType = flowType;
        this.reason = reason;
        this.operator = operator;
        this.requestId = requestId;
    }

    public Long getTicketId() { return ticketId; }
    public FlowType getFlowType() { return flowType; }
    public String getReason() { return reason; }
    public String getOperator() { return operator; }
    public String getRequestId() { return requestId; }
}
