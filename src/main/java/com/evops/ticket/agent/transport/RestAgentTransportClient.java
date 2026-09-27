package com.evops.ticket.agent.transport;

import com.evops.ticket.agent.config.AgentProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * 基于 {@link RestTemplate} 的平台传输实现。报文按 application/xml;charset=UTF-8 收发。
 */
@Component
public class RestAgentTransportClient implements AgentTransportClient {

    private final RestTemplate restTemplate;
    private final AgentProperties properties;

    public RestAgentTransportClient(AgentProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public SendResult send(String endpoint, String xml) {
        HttpHeaders headers = new HttpHeaders();
        MediaType xmlUtf8 = new MediaType(MediaType.APPLICATION_XML, StandardCharsets.UTF_8);
        headers.setContentType(xmlUtf8);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_XML));
        HttpEntity<String> entity = new HttpEntity<>(xml, headers);
        try {
            String body = restTemplate.postForObject(endpoint, entity, String.class);
            return new SendResult(200, body);
        } catch (HttpStatusCodeException e) {
            // 平台有应答但状态码非 2xx：保留状态码与响应体原文，交由上层判重试
            return new SendResult(e.getRawStatusCode(), e.getResponseBodyAsString(StandardCharsets.UTF_8));
        } catch (ResourceAccessException e) {
            throw new TransportException("连接代理平台失败：" + e.getMessage(), e);
        }
    }
}
