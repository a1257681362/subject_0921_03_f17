package com.evops.config;

import org.apache.shiro.realm.Realm;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.spring.web.config.DefaultShiroFilterChainDefinition;
import org.apache.shiro.spring.web.config.ShiroFilterChainDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API（/api/**）一律 anon 放行到 Spring MVC：场馆后台认证已由 JWT 双令牌接管
 * （见 JwtAuthInterceptor），不再走 Shiro 表单登录；/** 的兜底 authc 仅保留给非 API 路径。
 */
@Configuration
public class ShiroConfig {
    @Bean
    public Realm localRealm() {
        SimpleAccountRealm realm = new SimpleAccountRealm();
        realm.addAccount("bootstrap", "bootstrap", "system");
        return realm;
    }

    @Bean
    public ShiroFilterChainDefinition shiroFilterChainDefinition() {
        DefaultShiroFilterChainDefinition chain = new DefaultShiroFilterChainDefinition();
        chain.addPathDefinition("/api/health", "anon");
        chain.addPathDefinition("/api/auth/**", "anon");
        chain.addPathDefinition("/api/agent/**", "anon");
        chain.addPathDefinition("/api/ticket/**", "anon");
        chain.addPathDefinition("/api/admin/**", "anon");
        // 邮件网关联调桩：非业务 API，供 RestTemplate 投递/验收查看，不走后台 JWT
        chain.addPathDefinition("/mail-stub/**", "anon");
        chain.addPathDefinition("/error", "anon");
        chain.addPathDefinition("/**", "authc");
        return chain;
    }
}
