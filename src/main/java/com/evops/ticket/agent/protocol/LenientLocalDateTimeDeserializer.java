package com.evops.ticket.agent.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 容错的本地时间反序列化：优先平台规范 {@code yyyy-MM-dd HH:mm:ss}，
 * 兼受 ISO-8601 {@code yyyy-MM-ddTHH:mm:ss}；都不匹配则抛解析异常（进异常清单）。
 */
public class LenientLocalDateTimeDeserializer extends StdDeserializer<LocalDateTime> {

    private static final DateTimeFormatter PLATFORM =
            DateTimeFormatter.ofPattern(AgentXmlSpec.DATE_TIME_PATTERN);

    public LenientLocalDateTimeDeserializer() {
        super(LocalDateTime.class);
    }

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, PLATFORM);
        } catch (DateTimeParseException ignored) {
            // 继续尝试 ISO 形式
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw ctxt.weirdStringException(value, LocalDateTime.class,
                    "时间格式应为 " + AgentXmlSpec.DATE_TIME_PATTERN);
        }
    }
}
