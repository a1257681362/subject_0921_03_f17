package com.evops.ticket.agent.protocol;

/**
 * 代理售票平台 XML 规范常量。
 */
public final class AgentXmlSpec {

    private AgentXmlSpec() {
    }

    /** 平台约定的 XML 命名空间。 */
    public static final String NAMESPACE = "http://schema.evops.com/ticket/v1";

    /** 平台规范时间格式：yyyy-MM-dd HH:mm:ss（本地时间，无时区后缀）。 */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    /** 发送方标识：场馆 */
    public static final String SOURCE_VENUE = "VENUE";

    /** 发送方标识：代理平台 */
    public static final String SOURCE_AGENT = "AGENT";

    // ---- 报文类型 ----
    public static final String TYPE_TICKET_ISSUE = "TICKET_ISSUE";
    public static final String TYPE_TICKET_CHECK = "TICKET_CHECK";
    public static final String TYPE_TICKET_REFUND = "TICKET_REFUND";
    public static final String TYPE_TICKET_VOID = "TICKET_VOID";
    public static final String TYPE_TICKET_QUERY = "TICKET_QUERY";

    // ---- ACK 结果码 ----
    public static final String ACK_SUCCESS = "SUCCESS";
    public static final String ACK_DUPLICATE = "DUPLICATE";
    public static final String ACK_REJECTED = "REJECTED";
}
