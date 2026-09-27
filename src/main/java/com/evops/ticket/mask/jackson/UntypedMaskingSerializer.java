package com.evops.ticket.mask.jackson;

import com.evops.ticket.mask.service.MaskService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * 静态类型为 {@code Object} 的属性（如 {@code ApiResponse.data}）运行期可能携带
 * 幂等快照反序列化出来的 Map/List——Jackson 对 Object 属性不回调类型级
 * modifySerializer，只能在属性层（BeanPropertyWriter）挂此序列化器：
 * 先递归脱敏，再按运行时类型动态委托 Jackson 原生序列化器输出。
 * Map 走 MapSerializer（递归回到本模块的 Map 包装但已无敏感键可改），标量走默认。
 */
public class UntypedMaskingSerializer extends JsonSerializer<Object> {

    private final MaskTreeTransformer transformer;

    public UntypedMaskingSerializer(MaskService maskService) {
        this.transformer = new MaskTreeTransformer(maskService);
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        Object out;
        try {
            out = transformer.transform(value);
        } catch (Exception e) {
            out = value;
        }
        if (out == null) {
            serializers.defaultSerializeNull(gen);
            return;
        }
        // defaultSerializeValue 带完整上下文按运行时类型解析（LinkedHashMap→MapSerializer，
        // 标量→默认）；findValueSerializer 直接拿到的 MapSerializer 缺属性上下文会 NPE。
        serializers.defaultSerializeValue(out, gen);
    }
}
