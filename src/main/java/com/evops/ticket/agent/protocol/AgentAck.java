package com.evops.ticket.agent.protocol;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import java.time.LocalDateTime;

/**
 * 平台/场馆对一条报文的应答：
 * <pre>{@code
 * <agentAck xmlns=".../ticket/v1">
 *   <header>
 *     <messageId>ACK-...</messageId>
 *     <correlationId>原报文 messageId</correlationId>
 *     <timestamp>2026-09-23 19:30:00</timestamp>
 *   </header>
 *   <result>
 *     <code>SUCCESS</code>           <!-- SUCCESS / DUPLICATE / REJECTED -->
 *     <message>受理成功</message>
 *     <ticketNo>TSH002000000001</ticketNo>
 *   </result>
 * </agentAck>
 * }</pre>
 */
@JacksonXmlRootElement(localName = "agentAck", namespace = AgentXmlSpec.NAMESPACE)
public class AgentAck {

    @JacksonXmlProperty(localName = "header", namespace = AgentXmlSpec.NAMESPACE)
    private AckHeader header;

    @JacksonXmlProperty(localName = "result", namespace = AgentXmlSpec.NAMESPACE)
    private AckResult result;

    public AckHeader getHeader() { return header; }
    public void setHeader(AckHeader header) { this.header = header; }
    public AckResult getResult() { return result; }
    public void setResult(AckResult result) { this.result = result; }

    public static class AckHeader {
        @JacksonXmlProperty(localName = "messageId", namespace = AgentXmlSpec.NAMESPACE)
        private String messageId;

        @JacksonXmlProperty(localName = "correlationId", namespace = AgentXmlSpec.NAMESPACE)
        private String correlationId;

        @JacksonXmlProperty(localName = "timestamp", namespace = AgentXmlSpec.NAMESPACE)
        private LocalDateTime timestamp;

        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }
        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
        public LocalDateTime getTimestamp() { return timestamp; }
        public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
    }

    public static class AckResult {
        /** SUCCESS 受理成功 / DUPLICATE 重复报文幂等忽略 / REJECTED 拒绝（非法状态等） */
        @JacksonXmlProperty(localName = "code", namespace = AgentXmlSpec.NAMESPACE)
        private String code;

        @JacksonXmlProperty(localName = "message", namespace = AgentXmlSpec.NAMESPACE)
        private String message;

        @JacksonXmlProperty(localName = "ticketNo", namespace = AgentXmlSpec.NAMESPACE)
        private String ticketNo;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getTicketNo() { return ticketNo; }
        public void setTicketNo(String ticketNo) { this.ticketNo = ticketNo; }
    }
}
