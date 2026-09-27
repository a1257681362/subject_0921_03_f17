package com.evops.ticket.mask.jackson;

import com.evops.ticket.mask.MaskType;
import com.evops.ticket.mask.annotation.Sensitive;
import com.evops.ticket.mask.service.MaskService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.lang.reflect.Field;

/**
 * 敏感字段序列化器：出系统值统一委托 {@link MaskService} 判定（打码或白名单放行+留痕）。
 * 业务主键（预约号等）经 Jackson 当前序列化对象（gen.currentValue）读取
 * {@link Sensitive#bizIdField()} 指向的属性。
 */
public class SensitiveFieldSerializer extends JsonSerializer<Object> {

    private final MaskService maskService;
    private final Sensitive annotation;
    private final String fieldName;
    private final Field bizIdField;

    public SensitiveFieldSerializer(MaskService maskService, Sensitive annotation,
                                    String fieldName, Class<?> beanClass) {
        this.maskService = maskService;
        this.annotation = annotation;
        this.fieldName = fieldName;
        this.bizIdField = resolveBizIdField(beanClass, annotation.bizIdField());
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
            return;
        }
        String raw = String.valueOf(value);
        String bizId = readBizId(gen);
        String out = maskService.evaluate(fieldName, annotation.value(),
                annotation.bizType(), bizId, raw);
        gen.writeString(out);
    }

    private String readBizId(JsonGenerator gen) {
        if (bizIdField == null) {
            return null;
        }
        Object bean = gen.getCurrentValue();
        if (bean == null) {
            return null;
        }
        try {
            Object v = bizIdField.get(bean);
            return v == null ? null : String.valueOf(v);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Field resolveBizIdField(Class<?> beanClass, String bizIdField) {
        if (bizIdField == null || bizIdField.isEmpty()) {
            return null;
        }
        Class<?> c = beanClass;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(bizIdField);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
