package com.evops.ticket.mask.jackson;

import com.evops.ticket.mask.annotation.Sensitive;
import com.evops.ticket.mask.service.MaskService;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bean 序列化修改器（脱敏模块的核心挂载点）：
 * <ul>
 *   <li>{@link #changeProperties}：含 {@link Sensitive} 的 String 属性，挂
 *       {@link SensitiveFieldSerializer} 替换默认输出；其余属性原样；</li>
 *   <li>{@link #modifySerializer}：Map 包 {@link MaskingMapSerializer}，
 *       覆盖幂等快照回放（Map 结构无字段注解可走）。</li>
 * </ul>
 */
public class SensitiveSerializerModifier extends BeanSerializerModifier {

    private final MaskService maskService;

    public SensitiveSerializerModifier(MaskService maskService) {
        this.maskService = maskService;
    }

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config, BeanDescription beanDesc,
                                                     List<BeanPropertyWriter> beanProperties) {
        Map<String, Sensitive> sensitive = scanSensitive(beanDesc.getBeanClass());
        boolean needUntyped = hasObjectProperty(beanProperties);
        if (sensitive.isEmpty() && !needUntyped) {
            return beanProperties;
        }
        List<BeanPropertyWriter> out = new ArrayList<>(beanProperties.size());
        for (BeanPropertyWriter writer : beanProperties) {
            Sensitive ann = sensitive.get(writer.getName());
            if (ann != null) {
                // assignSerializer 原地把字段序列化器挂到 writer 上
                writer.assignSerializer(new SensitiveFieldSerializer(
                        maskService, ann, writer.getName(), beanDesc.getBeanClass()));
            } else if (writer.getType().getRawClass() == Object.class) {
                // Object 属性（ApiResponse.data 等）：运行期可能是幂等快照 Map，递归脱敏
                writer.assignSerializer(new UntypedMaskingSerializer(maskService));
            }
            out.add(writer);
        }
        return out;
    }

    private boolean hasObjectProperty(List<BeanPropertyWriter> writers) {
        for (BeanPropertyWriter w : writers) {
            if (w.getType().getRawClass() == Object.class) {
                return true;
            }
        }
        return false;
    }

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        if (Map.class.isAssignableFrom(beanDesc.getBeanClass())) {
            return new MaskingMapSerializer(serializer, maskService);
        }
        return serializer;
    }

    /** 字段名 -> 敏感注解（沿父类链扫描，String 字段才生效）。 */
    private Map<String, Sensitive> scanSensitive(Class<?> beanClass) {
        Map<String, Sensitive> map = new LinkedHashMap<>();
        Class<?> c = beanClass;
        while (c != null && c != Object.class) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                Sensitive ann = f.getAnnotation(Sensitive.class);
                if (ann != null && f.getType() == String.class) {
                    map.putIfAbsent(f.getName(), ann);
                }
            }
            c = c.getSuperclass();
        }
        return map;
    }
}
