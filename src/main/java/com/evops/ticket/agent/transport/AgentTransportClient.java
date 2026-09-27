package com.evops.ticket.agent.transport;

/**
 * 代理平台报文传输。抽成接口便于联调/测试以桩实现替换真实 HTTP。
 */
public interface AgentTransportClient {

    /**
     * 以 application/xml 投递报文到平台。
     *
     * @return 平台响应（含 HTTP 状态码与响应体原文）
     * @throws TransportException 网络层失败（连不上、超时）
     */
    SendResult send(String endpoint, String xml);
}
