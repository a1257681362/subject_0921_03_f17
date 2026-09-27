package com.evops.ticket.notify.channel;

import com.evops.ticket.notify.config.NotifyProperties;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.enums.NotifyChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 邮件通道：经 HTTP 邮件网关投递（演示/联调指向网关桩；真实部署由运维把
 * endpoint 配成企业 SMTP 出口网关地址）。渲染快照即邮件标题与正文，
 * 网关 2xx 且回执 code=OK 判成功，其余（含连接失败）进入退避重试。
 */
@Component
public class EmailChannelSender implements NotifyChannelSender {

    private static final Logger log = LoggerFactory.getLogger(EmailChannelSender.class);

    private final NotifyProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public EmailChannelSender(NotifyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutMs());
        this.restTemplate = new RestTemplate(factory);
    }

    @Override
    public String channel() {
        return NotifyChannel.EMAIL.name();
    }

    @Override
    public ChannelSendResult send(NotifyRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", properties.getMailFrom());
        payload.put("to", record.getRecipientAddress());
        payload.put("subject", record.getTitleRendered());
        payload.put("text", record.getContentRendered());
        payload.put("bizKey", record.getTicketNo());

        HttpHeaders headers = new HttpHeaders();
        MediaType jsonUtf8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
        headers.setContentType(jsonUtf8);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return ChannelSendResult.fail(null, "邮件载荷序列化失败：" + e.getMessage());
        }

        String responseBody;
        try {
            responseBody = restTemplate.postForObject(
                    properties.getEmailEndpoint(), new HttpEntity<>(body, headers), String.class);
        } catch (HttpStatusCodeException e) {
            String resp = e.getResponseBodyAsString(StandardCharsets.UTF_8);
            log.warn("邮件网关返回非 2xx recordId={} status={}", record.getId(), e.getRawStatusCode());
            return ChannelSendResult.fail(e.getRawStatusCode(),
                    "邮件网关 HTTP " + e.getRawStatusCode() + "：" + abbreviate(resp));
        } catch (ResourceAccessException e) {
            log.warn("邮件网关连接失败 recordId={} err={}", record.getId(), e.getMessage());
            return ChannelSendResult.fail(null, "连接邮件网关失败：" + abbreviate(e.getMessage()));
        }
        return parseAck(responseBody);
    }

    /** 网关 2xx：回执 JSON {"code":"OK","messageId":"..."}；无回执体按送达成功处理（与平台 ACK 同口径）。 */
    private ChannelSendResult parseAck(String responseBody) {
        if (responseBody == null || responseBody.trim().isEmpty() || !responseBody.contains("{")) {
            return ChannelSendResult.ok(200, null);
        }
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            JsonNode code = node.get("code");
            if (code != null && !"OK".equalsIgnoreCase(code.asText()) && !"SUCCESS".equalsIgnoreCase(code.asText())) {
                JsonNode msg = node.get("message");
                return ChannelSendResult.fail(200,
                        "邮件网关拒收：" + (msg == null ? code.asText() : abbreviate(msg.asText())));
            }
            JsonNode messageId = node.get("messageId");
            return ChannelSendResult.ok(200, messageId == null ? null : messageId.asText());
        } catch (Exception e) {
            // 非 JSON 回执（网关纯 OK）按送达成功处理
            return ChannelSendResult.ok(200, null);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 1900 ? text.substring(0, 1900) : text;
    }
}
