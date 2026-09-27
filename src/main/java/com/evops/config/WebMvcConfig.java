package com.evops.config;

import com.evops.auth.service.AuthService;
import com.evops.auth.web.JwtAuthInterceptor;
import com.evops.common.web.RequestContextInterceptor;
import com.evops.rbac.mapper.RbacDenyLogMapper;
import com.evops.rbac.web.RbacInterceptor;
import com.evops.ticket.mask.mapper.SensitiveViewLogMapper;
import com.evops.ticket.mask.web.MaskLogInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final SensitiveViewLogMapper viewLogMapper;
    private final RbacDenyLogMapper denyLogMapper;

    public WebMvcConfig(AuthService authService, ObjectMapper objectMapper,
                        SensitiveViewLogMapper viewLogMapper, RbacDenyLogMapper denyLogMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
        this.viewLogMapper = viewLogMapper;
        this.denyLogMapper = denyLogMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先认证后取业务上下文：RequestContextInterceptor 需要读取 AuthContext 绑定操作人
        registry.addInterceptor(new JwtAuthInterceptor(authService, objectMapper))
                .addPathPatterns(
                        "/api/ticket/**",
                        "/api/admin/**",
                        "/api/auth/logout",
                        "/api/auth/change-password",
                        "/api/auth/me",
                        "/api/auth/menus")
                .order(0);

        registry.addInterceptor(new RequestContextInterceptor())
                .addPathPatterns("/api/**")
                .order(1);

        // 脱敏上下文：认证/请求上下文之后建立（据此判定岗位并集并收集明文查看留痕）
        registry.addInterceptor(new MaskLogInterceptor(viewLogMapper))
                .addPathPatterns("/api/ticket/**")
                .order(2);

        // 统一权限：认证与上下文齐备后，按注解要求的权限码与用户并集判定；越权统一 403 + 留痕
        registry.addInterceptor(new RbacInterceptor(denyLogMapper, objectMapper))
                .addPathPatterns("/api/ticket/**", "/api/admin/**", "/api/auth/menus")
                .order(3);
    }
}
