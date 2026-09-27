package com.evops.ticket.mask.jackson;

import com.evops.ticket.mask.service.MaskService;
import com.fasterxml.jackson.databind.module.SimpleModule;

/**
 * 检票数据出系统脱敏 Jackson 模块。
 *
 * <p><b>只</b>注册到对外 HTTP 响应使用的 ObjectMapper 副本（见
 * {@code MaskWebMvcConfig}），绝不进 Spring 主 ObjectMapper：库内幂等快照存证、
 * 邮件载荷、平台 XML 报文等内部序列化保持完整值，脱敏只发生在「出系统」边界。
 */
public class SensitiveMaskModule extends SimpleModule {

    public SensitiveMaskModule(MaskService maskService) {
        super("sensitive-mask-module");
        setSerializerModifier(new SensitiveSerializerModifier(maskService));
    }
}
