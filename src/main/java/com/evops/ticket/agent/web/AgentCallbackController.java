package com.evops.ticket.agent.web;

import com.evops.ticket.agent.protocol.XmlMessageCodec;
import com.evops.ticket.agent.service.InboundAgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 代理售票平台回调入口（平台 -> 场馆）。匿名、不经过后台 JWT，供平台服务器直接投递。
 * 收 application/xml（兼 text/xml），回 application/xml 的 ACK；无论受理与否 HTTP 均 200，
 * 结果由 ACK result.code 表达（SUCCESS / DUPLICATE / REJECTED），坏报文已进异常清单。
 */
@RestController
@RequestMapping("/api/agent/v1")
public class AgentCallbackController {

    private static final Logger log = LoggerFactory.getLogger(AgentCallbackController.class);

    private final InboundAgentService inboundService;
    private final XmlMessageCodec codec;

    public AgentCallbackController(InboundAgentService inboundService, XmlMessageCodec codec) {
        this.inboundService = inboundService;
        this.codec = codec;
    }

    @PostMapping(value = "/messages",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.ALL_VALUE},
            produces = MediaType.APPLICATION_XML_VALUE + ";charset=UTF-8")
    public String receive(@RequestBody(required = false) String rawXml, HttpServletRequest request) {
        String xml = rawXml == null ? "" : rawXml;
        log.info("收到代理平台报文 length={}", xml.length());
        InboundAgentService.InboundResult result = inboundService.receive(xml, request.getRequestURI());
        return codec.toXml(result.getAck());
    }
}
