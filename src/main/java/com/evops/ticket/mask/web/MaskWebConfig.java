package com.evops.ticket.mask.web;

import com.evops.ticket.mask.jackson.SensitiveMaskModule;
import com.evops.ticket.mask.service.MaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

/**
 * 对外 HTTP JSON 序列化装配：复制 Spring 主 ObjectMapper（保留时间/金额等全部既有
 * 配置）后注册脱敏模块，仅供 MVC 的 JSON 消息转换器使用。
 *
 * <p>主 ObjectMapper 保持干净——库内幂等快照存证、邮件载荷、平台 XML 等内部
 * 序列化不经此转换器；脱敏只发生在「出系统」的 HTTP 边界。
 */
@Configuration
public class MaskWebConfig {

    @Bean
    public MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter(
            ObjectMapper objectMapper, MaskService maskService) {
        ObjectMapper webMapper = objectMapper.copy().registerModule(new SensitiveMaskModule(maskService));
        return new MappingJackson2HttpMessageConverter(webMapper);
    }
}
