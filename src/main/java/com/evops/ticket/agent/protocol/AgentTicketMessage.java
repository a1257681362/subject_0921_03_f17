package com.evops.ticket.agent.protocol;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.time.LocalDateTime;

/**
 * 代理售票平台报文信封（双向统一）。对应规范：
 * <pre>{@code
 * <agentMessage xmlns=".../ticket/v1">
 *   <header> ... </header>
 *   <body>   <ticket> ... </ticket> </body>
 * </agentMessage>
 * }</pre>
 * 字段命名与层级严格按平台规范：header 放路由/溯源字段，body 放业务票体。
 */
@JacksonXmlRootElement(localName = "agentMessage", namespace = AgentXmlSpec.NAMESPACE)
public class AgentTicketMessage {

    @JacksonXmlProperty(localName = "header", namespace = AgentXmlSpec.NAMESPACE)
    private MessageHeader header;

    @JacksonXmlProperty(localName = "body", namespace = AgentXmlSpec.NAMESPACE)
    private MessageBody body;

    public MessageHeader getHeader() { return header; }
    public void setHeader(MessageHeader header) { this.header = header; }
    public MessageBody getBody() { return body; }
    public void setBody(MessageBody body) { this.body = body; }

    /** 路由与溯源头：报文类型、收发方、时间戳、关联号。 */
    public static class MessageHeader {
        /** 报文唯一号（幂等键，重复投递只受理一次） */
        @JacksonXmlProperty(localName = "messageId", namespace = AgentXmlSpec.NAMESPACE)
        private String messageId;

        /** TICKET_ISSUE / TICKET_CHECK / TICKET_REFUND / TICKET_VOID / TICKET_QUERY */
        @JacksonXmlProperty(localName = "messageType", namespace = AgentXmlSpec.NAMESPACE)
        private String messageType;

        /** 平台规范时间格式 yyyy-MM-dd HH:mm:ss */
        @JacksonXmlProperty(localName = "timestamp", namespace = AgentXmlSpec.NAMESPACE)
        private LocalDateTime timestamp;

        /** 发送方：VENCE 场馆 / AGENT 代理平台 */
        @JacksonXmlProperty(localName = "source", namespace = AgentXmlSpec.NAMESPACE)
        private String source;

        @JacksonXmlProperty(localName = "target", namespace = AgentXmlSpec.NAMESPACE)
        private String target;

        /** 关联号：回调报文回填原通知的 messageId，便于串联一次交互 */
        @JacksonXmlProperty(localName = "correlationId", namespace = AgentXmlSpec.NAMESPACE)
        private String correlationId;

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    }

    /** 业务体：当前对接的票类报文均携带单张票。 */
    public static class MessageBody {
        @JacksonXmlProperty(localName = "ticket", namespace = AgentXmlSpec.NAMESPACE)
        private XmlTicket ticket;

        public XmlTicket getTicket() { return ticket; }
        public void setTicket(XmlTicket ticket) { this.ticket = ticket; }
    }
}
