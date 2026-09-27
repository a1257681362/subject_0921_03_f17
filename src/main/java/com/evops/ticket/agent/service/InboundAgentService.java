package com.evops.ticket.agent.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.agent.entity.AgentMessage;
import com.evops.ticket.agent.entity.AgentParseError;
import com.evops.ticket.agent.enums.ErrorStatus;
import com.evops.ticket.agent.enums.ErrorType;
import com.evops.ticket.agent.enums.MessageDirection;
import com.evops.ticket.agent.enums.MessageStatus;
import com.evops.ticket.agent.protocol.AgentAck;
import com.evops.ticket.agent.protocol.AgentTicketMessage;
import com.evops.ticket.agent.protocol.AgentXmlSpec;
import com.evops.ticket.agent.protocol.OutboundMessageFactory;
import com.evops.ticket.agent.protocol.XmlMessageCodec;
import com.evops.ticket.agent.protocol.XmlTicket;
import com.evops.ticket.dto.ticket.TicketActionRequest;
import com.evops.ticket.service.TicketService;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 入站报文（平台 -> 场馆）受理：
 * <ol>
 *   <li><b>先存原文</b>：结构是否解析成功都留档，按票号可回查；</li>
 *   <li>messageId 幂等：重复投递回 DUPLICATE，不重复处理；</li>
 *   <li>结构坏 → PARSE_ERROR；字段不合规 → VALIDATION_ERROR；票状态机失败 → BIZ_ERROR，统一进异常清单；</li>
 *   <li>受理成功复用场馆既有票状态机（检票/退票/作废），回 SUCCESS ACK。</li>
 * </ol>
 * 归档与异常登记走 REQUIRES_NEW，业务回滚不影响留档。
 */
@Service
public class InboundAgentService {

    private static final Logger log = LoggerFactory.getLogger(InboundAgentService.class);

    /** 场馆为票源，入站仅受理平台侧发生的票状态同步；出票须由场馆建档。 */
    private static final Set<String> INBOUND_TYPES = new HashSet<>(Arrays.asList(
            AgentXmlSpec.TYPE_TICKET_CHECK,
            AgentXmlSpec.TYPE_TICKET_REFUND,
            AgentXmlSpec.TYPE_TICKET_VOID));

    private final XmlMessageCodec codec;
    private final AgentMessageStore store;
    private final OutboundMessageFactory factory;
    private final TicketService ticketService;

    public InboundAgentService(XmlMessageCodec codec, AgentMessageStore store,
                               OutboundMessageFactory factory, TicketService ticketService) {
        this.codec = codec;
        this.store = store;
        this.factory = factory;
        this.ticketService = ticketService;
    }

    /** 受理结果：ACK（控制器序列化为 XML）+ 是否重复。 */
    public static class InboundResult {
        private final AgentAck ack;
        private final boolean duplicate;

        public InboundResult(AgentAck ack, boolean duplicate) {
            this.ack = ack;
            this.duplicate = duplicate;
        }

        public AgentAck getAck() { return ack; }
        public boolean isDuplicate() { return duplicate; }
    }

    public InboundResult receive(String rawXml, String endpoint) {
        // 1) 先解析；坏结构立即进异常清单（尽量从原文抽 messageId/ticketNo 定位）
        AgentTicketMessage message;
        try {
            message = codec.parse(rawXml);
        } catch (JsonProcessingException e) {
            String messageId = XmlMessageCodec.extractTag(rawXml, "messageId");
            String ticketNo = XmlMessageCodec.extractTag(rawXml, "ticketNo");
            recordError(ErrorType.PARSE_ERROR, messageId, ticketNo,
                    XmlMessageCodec.extractTag(rawXml, "messageType"), rawXml,
                    "XML 解析失败：" + abbreviate(e.getOriginalMessage()));
            return new InboundResult(
                    reject(messageId, ticketNo, "报文 XML 结构无法解析，已登记异常清单"), false);
        }

        AgentTicketMessage.MessageHeader header = message.getHeader();
        XmlTicket ticket = message.getBody() == null ? null : message.getBody().getTicket();
        String messageId = header == null ? null : trimToNull(header.getMessageId());
        String ticketNo = ticket == null ? null : trimToNull(ticket.getTicketNo());
        String messageType = header == null ? null : trimToNull(header.getMessageType());

        // 2) messageId 幂等优先：只要带了已受理过的 messageId（含此前被拒的报文），重复投递即忽略，
        //    避免对一条已知报文重复登记/报错；并发重复由归档唯一约束兜底。
        if (messageId != null) {
            AgentMessage existing = store.findByMessageId(messageId, MessageDirection.INBOUND.name());
            if (existing != null) {
                log.info("入站报文重复投递，幂等忽略 messageId={} ticketNo={}", messageId, ticketNo);
                return new InboundResult(
                        ack(AgentXmlSpec.ACK_DUPLICATE, messageId, ticketNo, "重复报文，已忽略"), true);
            }
        }

        // 3) 字段合规校验
        String validationError = validate(header, ticket, messageType);
        if (validationError != null) {
            archiveInbound(rawXml, endpoint, messageId, messageType, ticketNo, MessageStatus.FAILED);
            recordError(ErrorType.VALIDATION_ERROR, messageId, ticketNo, messageType, rawXml, validationError);
            return new InboundResult(reject(messageId, ticketNo, validationError), false);
        }

        // 4) 原文先归档（独立事务），再处理业务；并发重复投递由唯一约束兜底
        try {
            archiveInbound(rawXml, endpoint, messageId, messageType, ticketNo, MessageStatus.PENDING);
        } catch (IllegalArgumentException alreadyExists) {
            return new InboundResult(
                    ack(AgentXmlSpec.ACK_DUPLICATE, messageId, ticketNo, "重复报文，已忽略"), true);
        }

        try {
            applyStatusChange(messageType, ticket);
        } catch (IllegalArgumentException e) {
            // 票不存在 / 非法状态流转 / 乐观锁冲突等业务失败
            markArchive(messageId, MessageStatus.FAILED);
            recordError(ErrorType.BIZ_ERROR, messageId, ticketNo, messageType, rawXml,
                    abbreviate(e.getMessage()));
            return new InboundResult(reject(messageId, ticketNo, e.getMessage()), false);
        } catch (Exception e) {
            log.error("入站报文处理系统异常 messageId={}", messageId, e);
            markArchive(messageId, MessageStatus.FAILED);
            recordError(ErrorType.BIZ_ERROR, messageId, ticketNo, messageType, rawXml,
                    abbreviate("系统处理失败：" + e.getMessage()));
            return new InboundResult(reject(messageId, ticketNo, "系统处理失败，已登记异常清单"), false);
        }

        markArchive(messageId, MessageStatus.SUCCESS);
        return new InboundResult(
                ack(AgentXmlSpec.ACK_SUCCESS, messageId, ticketNo, "受理成功"), false);
    }

    /** 复用票状态机；操作人/请求号取报文口径，保证流水可追溯到平台报文。 */
    private void applyStatusChange(String messageType, XmlTicket ticket) {
        RequestContext.Context previous = RequestContext.get();
        String operator = trimToNull(ticket.getOperator());
        String requestId = "AGENT:" + ticket.getTicketNo() + ":" + System.nanoTime();
        try {
            RequestContext.set(new RequestContext.Context(
                    operator != null ? operator : "agent-platform", requestId, "/api/agent/v1/messages", 0L));
            // 标记为入站来源：本次票状态机提交后触发的生命周期事件不再回推平台，避免回声环路
            AgentInboundGuard.enter();

            TicketActionRequest action = new TicketActionRequest();
            action.setReason(trimToNull(ticket.getReason()));
            LocalDateTime bizTime = ticket.getBizTime();
            action.setBizTime(bizTime);

            switch (messageType) {
                case AgentXmlSpec.TYPE_TICKET_CHECK:
                    ticketService.check(ticket.getTicketNo(), action);
                    break;
                case AgentXmlSpec.TYPE_TICKET_REFUND:
                    ticketService.refund(ticket.getTicketNo(), action);
                    break;
                case AgentXmlSpec.TYPE_TICKET_VOID:
                    ticketService.voidTicket(ticket.getTicketNo(), action);
                    break;
                default:
                    throw new IllegalArgumentException("不支持的入站报文类型：" + messageType);
            }
        } finally {
            AgentInboundGuard.exit();
            if (previous != null) {
                RequestContext.set(previous);
            } else {
                RequestContext.clear();
            }
        }
    }

    private String validate(AgentTicketMessage.MessageHeader header, XmlTicket ticket, String messageType) {
        if (header == null) {
            return "缺少报文头 header";
        }
        if (trimToNull(header.getMessageId()) == null) {
            return "缺少 messageId";
        }
        if (messageType == null) {
            return "缺少 messageType";
        }
        if (!INBOUND_TYPES.contains(messageType)) {
            return "不支持的入站报文类型：" + messageType + "（仅受理 TICKET_CHECK/TICKET_REFUND/TICKET_VOID）";
        }
        if (ticket == null) {
            return "缺少票体 body.ticket";
        }
        if (trimToNull(ticket.getTicketNo()) == null) {
            return "缺少 ticketNo";
        }
        return null;
    }

    private void archiveInbound(String rawXml, String endpoint, String messageId, String messageType,
                                String ticketNo, MessageStatus status) {
        AgentMessage row = new AgentMessage();
        row.setMessageId(messageId == null ? "UNKNOWN-" + System.nanoTime() : messageId);
        row.setDirection(MessageDirection.INBOUND.name());
        row.setMessageType(messageType);
        row.setTicketNo(ticketNo);
        row.setEndpoint(endpoint);
        row.setPayload(rawXml);
        row.setStatus(status.name());
        row.setRetryCount(0);
        row.setMaxRetry(0);
        row.setOperator(RequestContext.operator());
        row.setRequestId(RequestContext.requestId());
        row.setBizTime(LocalDateTime.now());
        try {
            store.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 并发重复投递：唯一约束兜底，交由上层幂等判断（理论上前面已查重）
            throw new IllegalArgumentException("报文正在处理或已受理：messageId=" + messageId);
        }
    }

    private void markArchive(String messageId, MessageStatus status) {
        AgentMessage row = store.findByMessageId(messageId, MessageDirection.INBOUND.name());
        if (row != null) {
            store.markStatus(row.getId(), status.name());
        }
    }

    private void recordError(ErrorType type, String messageId, String ticketNo, String messageType,
                             String payload, String detail) {
        AgentParseError error = new AgentParseError();
        error.setDirection(MessageDirection.INBOUND.name());
        error.setErrorType(type.name());
        error.setMessageId(messageId);
        error.setTicketNo(ticketNo);
        error.setMessageType(messageType);
        error.setPayload(payload);
        error.setErrorDetail(detail);
        error.setStatus(ErrorStatus.OPEN.name());
        error.setOperator(RequestContext.operator());
        error.setRequestId(RequestContext.requestId());
        store.recordError(error);
        log.warn("代理报文异常登记 type={} messageId={} ticketNo={} detail={}",
                type, messageId, ticketNo, detail);
    }

    private AgentAck reject(String correlationId, String ticketNo, String message) {
        return ack(AgentXmlSpec.ACK_REJECTED, correlationId, ticketNo, message);
    }

    private AgentAck ack(String code, String correlationId, String ticketNo, String message) {
        AgentAck ack = new AgentAck();
        AgentAck.AckHeader h = new AgentAck.AckHeader();
        h.setMessageId(factory.newAckId());
        h.setCorrelationId(correlationId);
        h.setTimestamp(LocalDateTime.now());
        AgentAck.AckResult r = new AgentAck.AckResult();
        r.setCode(code);
        r.setMessage(message);
        r.setTicketNo(ticketNo);
        ack.setHeader(h);
        ack.setResult(r);
        return ack;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 1900 ? text.substring(0, 1900) : text;
    }
}
