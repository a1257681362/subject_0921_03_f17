package com.evops.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 幂等快照以 JSON 存库、回放时反序列化为 Map。开启浮点按 BigDecimal 还原，
 * 保证金额字段（DECIMAL(10,2)，如 180.00）回放后标度不变、响应与首次逐字节一致。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalForFloatsCustomizer() {
        return builder -> builder.featuresToEnable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
}
