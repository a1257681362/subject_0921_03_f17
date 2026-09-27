package com.evops.ticket.agent.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.agent.config.AgentProperties;
import com.evops.ticket.agent.entity.AgentMessage;
import com.evops.ticket.agent.enums.MessageDirection;
import com.evops.ticket.agent.enums.MessageStatus;
import com.evops.ticket.agent.protocol.AgentAck;
import com.evops.ticket.agent.protocol.AgentTicketMessage;
import com.evops.ticket.agent.protocol.AgentXmlSpec;
import com.evops.ticket.agent.protocol.OutboundMessageFactory;
import com.evops.ticket.agent.protocol.XmlMessageCodec;
import com.evops.ticket.agent.transport.AgentTransportClient;
import com.evops.ticket.agent.transport.SendResult;
import com.evops.ticket.agent.transport.TransportException;
import com.evops.ticket.entity.SaleOrder;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.mapper.SaleOrderMapper;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.ShowSessionMapper;
import com.evops.ticket.mapper.TicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出站报文：业务在事务提交后装配报文 -> 原文落 PENDING 归档 -> 立即投递；
 * 失败按指数退避置 RETRYING，到点由定时任务重投，超过上限置 FAILED 待人工重发。
 * 归档与投递解耦：发送在事务外进行（不持库连接等网络），归档写 REQUIRES_NEW，业务回滚不影响留档。
 */
@Service
public class OutboundAgentService {

    private static final Logger log = LoggerFactory.getLogger(OutboundAgentService.class);

    private final AgentMessageStore store;
    private final XmlMessageCodec codec;
    private final OutboundMessageFactory factory;
    private final AgentTransportClient transport;
    private final AgentProperties properties;
    private final TicketMapper ticketMapper;
    private final SaleOrderMapper orderMapper;
    private final ShowSessionMapper sessionMapper;
    private final SeatMapper seatMapper;

    public OutboundAgentService(AgentMessageStore store, XmlMessageCodec codec,
                                OutboundMessageFactory factory, AgentTransportClient transport,
                                AgentProperties properties, TicketMapper ticketMapper,
                                SaleOrderMapper orderMapper, ShowSessionMapper sessionMapper,
                                SeatMapper seatMapper) {
        this.store = store;
        this.codec = codec;
        this.factory = factory;
        this.transport = transport;
        this.properties = properties;
        this.ticketMapper = ticketMapper;
        this.orderMapper = orderMapper;
        this.sessionMapper = sessionMapper;
        this.seatMapper = seatMapper;
    }

    /** 票生命周期事件统一入口：ISSUE/CHECK/REFUND/VOID，自行装配订单/场次/座位。 */
    public void onTicketLifecycle(Ticket ticket, com.evops.ticket.enums.FlowType flowType, String reason) {
        SaleOrder order = orderMapper.selectById(ticket.getOrderId());
        ShowSession session = sessionMapper.selectById(ticket.getSessionId());
        Seat seat = seatMapper.selectById(ticket.getSeatId());
        AgentTicketMessage msg;
        if (flowType == com.evops.ticket.enums.FlowType.ISSUE) {
            msg = factory.issue(ticket, order, session, seat);
        } else {
            msg = factory.statusChange(mapType(flowType), ticket,
                    order == null ? null : order.getOrderNo(), session, seat, reason);
        }
        recordAndSend(msg, ticket.getTicketNo(), order == null ? null : order.getOrderNo(),
                session == null ? null : session.getSessionCode());
    }

    /**
     * 按票补推：依据票当前状态重新生成一条报文（新 messageId，重投安全）。
     * 用于历史票补同步或 FAILED 报文需要以最新数据重发。
     */
    public AgentMessage resendTicket(String ticketNo) {
        Ticket ticket = requireTicket(ticketNo);
        SaleOrder order = orderMapper.selectById(ticket.getOrderId());
        ShowSession session = sessionMapper.selectById(ticket.getSessionId());
        Seat seat = seatMapper.selectById(ticket.getSeatId());
        String type = mapTypeFromStatus(ticket.getStatus());
        AgentTicketMessage msg = factory.statusChange(type, ticket,
                order == null ? null : order.getOrderNo(), session, seat, null);
        return recordAndSend(msg, ticketNo, order == null ? null : order.getOrderNo(),
                session == null ? null : session.getSessionCode());
    }

    /** 归档原文并立即尝试投递一次。返回落库行（发送结果异步反映在其状态上）。 */
    private AgentMessage recordAndSend(AgentTicketMessage msg, String ticketNo, String orderNo,
                                       String sessionCode) {
        String xml = codec.toXml(msg);
        AgentMessage row = new AgentMessage();
        row.setMessageId(msg.getHeader().getMessageId());
        row.setDirection(MessageDirection.OUTBOUND.name());
        row.setMessageType(msg.getHeader().getMessageType());
        row.setTicketNo(ticketNo);
        row.setOrderNo(orderNo);
        row.setSessionCode(sessionCode);
        row.setCorrelationId(msg.getHeader().getCorrelationId());
        row.setEndpoint(properties.getEndpoint());
        row.setPayload(xml);
        row.setStatus(MessageStatus.PENDING.name());
        row.setRetryCount(0);
        row.setMaxRetry(properties.getMaxRetry());
        row.setOperator(RequestContext.operator());
        row.setRequestId(RequestContext.requestId());
        row.setBizTime(msg.getBody().getTicket().getBizTime());
        store.insert(row);

        dispatch(row);
        // 回读投递后的最新状态（dispatch 在独立事务里更新了该行），避免返回陈旧 PENDING
        return store.getById(row.getId());
    }

    /**
     * 实际投递并据结果更新归档状态。包可见，供定时任务与手动重发复用。
     * 无事务：网络调用不持库连接；状态更新走 store 的独立事务。
     */
    public void dispatch(AgentMessage row) {
        if (!properties.isEnabled()) {
            // 总开关关闭：仅留档，等开关打开后由定时任务或人工发送
            return;
        }
        SendResult result;
        try {
            result = transport.send(row.getEndpoint(), row.getPayload());
        } catch (TransportException e) {
            log.warn("出站报文网络失败 messageId={} ticketNo={} err={}",
                    row.getMessageId(), row.getTicketNo(), e.getMessage());
            scheduleRetry(row, null, e.getMessage());
            return;
        }

        if (result.is2xxSuccessful()) {
            // 平台明确业务拒收（ACK code=REJECTED）视为终态失败，重试同载荷无意义
            String rejectReason = checkRejected(result.getBody());
            if (rejectReason != null) {
                log.warn("出站报文被平台拒收 messageId={} reason={}", row.getMessageId(), rejectReason);
                store.updateSendResult(row.getId(), MessageStatus.FAILED.name(), result.getHttpStatus(),
                        row.getRetryCount(), null, abbreviate("平台拒收：" + rejectReason));
                return;
            }
            store.updateSendResult(row.getId(), MessageStatus.SUCCESS.name(), result.getHttpStatus(),
                    row.getRetryCount(), null, null);
            return;
        }

        scheduleRetry(row, result.getHttpStatus(),
                "平台返回 HTTP " + result.getHttpStatus() + "：" + abbreviate(safeBody(result.getBody())));
    }

    /** 定时任务：扫描到期出站报文重投。 */
    public int retryDue() {
        if (!properties.isEnabled()) {
            return 0;
        }
        List<AgentMessage> due = store.dueOutbound(properties.getRetryBatchSize());
        for (AgentMessage row : due) {
            try {
                dispatch(row);
            } catch (Exception e) {
                // 单条异常不影响整批；状态未更新则下一轮仍会捞到
                log.error("出站重试异常 messageId={}", row.getMessageId(), e);
            }
        }
        return due.size();
    }

    /** 人工重发：FAILED/PENDING/RETRYING 立即再投一次。 */
    public AgentMessage manualRetry(Long id) {
        AgentMessage row = store.getById(id);
        if (row == null) {
            throw new IllegalArgumentException("报文不存在：id=" + id);
        }
        if (!MessageDirection.OUTBOUND.name().equals(row.getDirection())) {
            throw new IllegalArgumentException("仅出站报文可重发");
        }
        if (MessageStatus.SUCCESS.name().equals(row.getStatus())) {
            throw new IllegalArgumentException("已成功送达的报文无需重发");
        }
        dispatch(row);
        return store.getById(id);
    }

    private void scheduleRetry(AgentMessage row, Integer httpStatus, String error) {
        int attempt = row.getRetryCount() == null ? 0 : row.getRetryCount();
        int next = attempt + 1;
        if (next > properties.getMaxRetry()) {
            store.updateSendResult(row.getId(), MessageStatus.FAILED.name(), httpStatus,
                    attempt, null, abbreviate(error));
            log.error("出站报文重试耗尽置 FAILED messageId={} ticketNo={} 最后错误={}",
                    row.getMessageId(), row.getTicketNo(), abbreviate(error));
            return;
        }
        LocalDateTime nextTime = LocalDateTime.now().plusSeconds(properties.backoffSeconds(next));
        store.updateSendResult(row.getId(), MessageStatus.RETRYING.name(), httpStatus,
                next, nextTime, abbreviate(error));
    }

    /** 平台 2xx 但 ACK=REJECTED 时取原因；非 ACK 体或 SUCCESS/DUPLICATE 返回 null。 */
    private String checkRejected(String body) {
        if (body == null || !body.contains("<") || !body.contains("agentAck")) {
            return null;
        }
        try {
            AgentAck ack = codec.parseAck(body);
            if (ack.getResult() != null && AgentXmlSpec.ACK_REJECTED.equals(ack.getResult().getCode())) {
                return ack.getResult().getMessage();
            }
        } catch (Exception ignored) {
            // 平台回非 ACK 体（如纯 OK）按送达成功处理
        }
        return null;
    }

    private Ticket requireTicket(String ticketNo) {
        LambdaQueryWrapper<Ticket> w = new LambdaQueryWrapper<>();
        w.eq(Ticket::getTicketNo, ticketNo).last("LIMIT 1");
        Ticket ticket = ticketMapper.selectOne(w);
        if (ticket == null) {
            throw new IllegalArgumentException("票不存在：" + ticketNo);
        }
        return ticket;
    }

    private String mapType(com.evops.ticket.enums.FlowType flowType) {
        switch (flowType) {
            case CHECK:
                return AgentXmlSpec.TYPE_TICKET_CHECK;
            case REFUND:
                return AgentXmlSpec.TYPE_TICKET_REFUND;
            case VOID:
                return AgentXmlSpec.TYPE_TICKET_VOID;
            case ISSUE:
            default:
                return AgentXmlSpec.TYPE_TICKET_ISSUE;
        }
    }

    private String mapTypeFromStatus(String status) {        if (status == null) {
            return AgentXmlSpec.TYPE_TICKET_ISSUE;
        }
        switch (status) {
            case "CHECKED":
                return AgentXmlSpec.TYPE_TICKET_CHECK;
            case "REFUNDED":
                return AgentXmlSpec.TYPE_TICKET_REFUND;
            case "VOID":
                return AgentXmlSpec.TYPE_TICKET_VOID;
            case "SOLD":
            default:
                return AgentXmlSpec.TYPE_TICKET_ISSUE;
        }
    }

    private static String safeBody(String body) {
        return body == null ? "" : body;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 1900 ? text.substring(0, 1900) : text;
    }
}
