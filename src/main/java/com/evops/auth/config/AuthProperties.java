package com.evops.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;

/**
 * 场馆后台 JWT 双令牌参数（前缀 evops.auth）。
 * 访问令牌短期有效；刷新令牌长期、服务端存证、一次性轮换。
 */
@Component
@ConfigurationProperties(prefix = "evops.auth")
public class AuthProperties {

    /** HMAC-SHA256 签名密钥；生产必须经 EVOPS_AUTH_SECRET 注入，禁止使用演示默认值 */
    private String secret;

    /** 访问令牌有效期（秒），默认 15 分钟 */
    private long accessTokenTtlSeconds = 900;

    /** 刷新令牌有效期（秒），默认 7 天 */
    private long refreshTokenTtlSeconds = 604800;

    /** JWT iss 声明 */
    private String issuer = "evops-venue-admin";

    @PostConstruct
    public void validate() {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("evops.auth.secret 未配置或长度不足 32 字节（请通过 EVOPS_AUTH_SECRET 注入）");
        }
        if (accessTokenTtlSeconds <= 0 || refreshTokenTtlSeconds <= 0
                || accessTokenTtlSeconds >= refreshTokenTtlSeconds) {
            throw new IllegalStateException("令牌有效期配置非法：访问令牌必须短期且短于刷新令牌");
        }
    }

    public byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public long getAccessTokenTtlSeconds() { return accessTokenTtlSeconds; }
    public void setAccessTokenTtlSeconds(long v) { this.accessTokenTtlSeconds = v; }
    public long getRefreshTokenTtlSeconds() { return refreshTokenTtlSeconds; }
    public void setRefreshTokenTtlSeconds(long v) { this.refreshTokenTtlSeconds = v; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
}
