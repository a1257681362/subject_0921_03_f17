package com.evops.ticket.agent.protocol;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * 票款金额反序列化，明确区分两种语义：
 * <ul>
 *   <li>{@code <price></price>}、{@code <price>  </price>} 或缺省 → {@code null}，
 *       语义为<b>平台未提供金额（未知）</b>，任何下游都不得把它当作 0 参与汇总；</li>
 *   <li>{@code <price>0</price>} → {@link BigDecimal#ZERO}，语义为平台明确给零（赠票/免票）；</li>
 *   <li>非数字文本 → 抛解析异常，按坏报文进异常清单，不静默吞掉。</li>
 * </ul>
 * 历史上曾出现空节点被下游按 0 入账、票款汇总整段少计的缺陷，
 * 该语义由 {@code XmlMessageCodecTest} 与入站集成测试用例锁死，勿改回宽松口径。
 */
public class EmptyAwareBigDecimalDeserializer extends StdDeserializer<BigDecimal> {

    public EmptyAwareBigDecimalDeserializer() {
        super(BigDecimal.class);
    }

    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getValueAsString();
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw ctxt.weirdStringException(value, BigDecimal.class, "票款金额应为数字");
        }
    }
}
