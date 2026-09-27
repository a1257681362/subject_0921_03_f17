package com.evops.ticket.mask.jackson;

import com.evops.ticket.mask.service.MaskService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Map;

/**
 * 静态类型为 Map 的属性脱敏包装：先递归变换敏感键，再委托 Jackson 原生
 * MapSerializer 输出（保留空值处理、排序等特性）。
 * 典型的幂等回放走 {@code ApiResponse.data:Object}，由 {@link UntypedMaskingSerializer}
 * 兜住；本包装覆盖 VO 上显式声明为 Map 类型的出口。
 */
public class MaskingMapSerializer extends JsonSerializer<Map<?, ?>> {

    private final JsonSerializer<Map<?, ?>> delegate;
    private final MaskTreeTransformer transformer;

    @SuppressWarnings("unchecked")
    public MaskingMapSerializer(JsonSerializer<?> delegate, MaskService maskService) {
        this.delegate = (JsonSerializer<Map<?, ?>>) delegate;
        this.transformer = new MaskTreeTransformer(maskService);
    }

    @Override
    public void serialize(Map<?, ?> map, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        Map<?, ?> out;
        try {
            out = (Map<?, ?>) transformer.transform(map);
        } catch (Exception e) {
            out = map;
        }
        delegate.serialize(out, gen, serializers);
    }
}
