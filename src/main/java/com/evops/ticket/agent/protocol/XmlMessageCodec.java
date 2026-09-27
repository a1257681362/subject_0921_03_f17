package com.evops.ticket.agent.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代理平台 XML 编解码：对象 ⇄ 报文双向映射。
 *
 * <ul>
 *   <li>独立 {@link XmlMapper}，与场馆后台 JSON 的 {@code ObjectMapper} 隔离，互不影响；</li>
 *   <li>统一 UTF-8，输出带 {@code <?xml ... encoding="UTF-8"?>} 声明，原文落库即字节口径；</li>
 *   <li>时间统一平台规范 {@code yyyy-MM-dd HH:mm:ss}；</li>
 *   <li>未知字段容错（前向兼容），必填校验在业务层做（VALIDATION_ERROR），
 *       仅结构性坏报文才判 PARSE_ERROR；</li>
 *   <li>坏报文无法绑定对象时，仍可用 {@link #extractTag} 兜底抽票号/报文号归档。</li>
 * </ul>
 */
@Component
public class XmlMessageCodec {

    private static final String XML_PROLOG = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    private static final Pattern PROLOG_PATTERN = Pattern.compile("^\\s*<\\?xml[^>]*\\?>");

    private final XmlMapper xmlMapper;

    public XmlMessageCodec() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(AgentXmlSpec.DATE_TIME_PATTERN);
        JavaTimeModule timeModule = new JavaTimeModule();
        timeModule.addSerializer(new LocalDateTimeSerializer(formatter));
        timeModule.addDeserializer(java.time.LocalDateTime.class, new LenientLocalDateTimeDeserializer());

        this.xmlMapper = XmlMapper.builder()
                .defaultUseWrapper(false)
                .build();
        this.xmlMapper.registerModule(timeModule);
        this.xmlMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 平台新增字段时旧版本不致整体解析失败；必填项由业务层显式校验
        this.xmlMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** 对象序列化为带 UTF-8 声明的规范 XML 字符串。 */
    public String toXml(Object value) {
        try {
            String body = xmlMapper.writeValueAsString(value);
            // 统一声明：去掉序列化器可能自带的无编码声明，换成显式 UTF-8 声明
            Matcher matcher = PROLOG_PATTERN.matcher(body);
            if (matcher.find()) {
                body = body.substring(matcher.end());
            }
            return XML_PROLOG + "\n" + body.trim();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("XML 序列化失败", e);
        }
    }

    /** 对象序列化为 UTF-8 字节（实际发送与落库均走此口径）。 */
    public byte[] toXmlBytes(Object value) {
        return toXml(value).getBytes(StandardCharsets.UTF_8);
    }

    /** 解析票报文；结构性坏报文抛 {@link JsonProcessingException}，由调用方进异常清单。 */
    public AgentTicketMessage parse(String xml) throws JsonProcessingException {
        return xmlMapper.readValue(xml, AgentTicketMessage.class);
    }

    /** 解析平台 ACK。 */
    public AgentAck parseAck(String xml) throws JsonProcessingException {
        return xmlMapper.readValue(xml, AgentAck.class);
    }

    /**
     * 坏报文兜底取标签值（结构已损坏、无法绑定对象时用于归档定位）。
     * 仅取简单文本标签，找不到返回 null。
     */
    public static String extractTag(String xml, String tag) {
        if (xml == null) {
            return null;
        }
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "\\s*>([^<]*)</" + Pattern.quote(tag) + ">")
                .matcher(xml);
        if (m.find()) {
            String v = m.group(1).trim();
            return v.isEmpty() ? null : v;
        }
        return null;
    }
}
