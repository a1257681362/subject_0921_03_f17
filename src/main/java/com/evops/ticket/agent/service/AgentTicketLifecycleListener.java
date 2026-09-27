package com.evops.ticket.agent.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.agent.config.AgentProperties;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.event.TicketLifecycleEvent;
import com.evops.ticket.mapper.TicketMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 票生命周期 -> 代理平台出站通知。
 *
 * <p>仅在票状态变更事务<b>提交后</b>触发（AFTER_COMMIT）：保证通知的状态已落库；
 * 事务回滚不通知。{@code fallbackExecution=true} 使无事务上下文（如补推/测试）也能调用。
 * 出站失败不影响票务主流程：报文已落 PENDING 归档，由定时任务退避重试。
 */
@Component
public class AgentTicketLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(AgentTicketLifecycleListener.class);

    private final OutboundAgentService outboundService;
    private final TicketMapper ticketMapper;
    private final AgentProperties properties;

    public AgentTicketLifecycleListener(OutboundAgentService outboundService,
                                        TicketMapper ticketMapper, AgentProperties properties) {
        this.outboundService = outboundService;
        this.ticketMapper = ticketMapper;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTicketLifecycle(TicketLifecycleEvent event) {
        // 入站（平台发起）的状态变更不再回推平台，避免回声环路
        if (AgentInboundGuard.isInbound()) {
            log.debug("入站来源的票变更跳过平台出站通知 ticketId={} flow={}",
                    event.getTicketId(), event.getFlowType());
            return;
        }
        try {
            Ticket ticket = ticketMapper.selectById(event.getTicketId());
            if (ticket == null) {
                log.warn("票生命周期事件找不到票，跳过平台通知 ticketId={}", event.getTicketId());
                return;
            }
            // 提交后阶段可能脱离请求线程上下文，用事件快照补齐审计口径
            RequestContext.Context previous = RequestContext.get();
            boolean setByListener = previous == null;
            if (setByListener) {
                RequestContext.set(new RequestContext.Context(
                        event.getOperator(), event.getRequestId(), "/agent/outbound", 0L));
            }
            try {
                outboundService.onTicketLifecycle(ticket, event.getFlowType(), event.getReason());
            } finally {
                if (setByListener) {
                    RequestContext.clear();
                }
            }
        } catch (Exception e) {
            // 对接异常不得反噬票务主事务（此时主事务已提交，仅兜底防日志噪音升级）
            log.error("票生命周期事件触发平台通知失败 ticketId={} flow={}",
                    event.getTicketId(), event.getFlowType(), e);
        }
    }
}
