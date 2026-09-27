package com.evops.auth.security;

import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 刷新令牌与 jti 生成；刷新令牌明文只在响应中出现一次，库存 SHA-256 摘要。
 */
@Component
public class TokenGenerator {

    private final SecureRandom random = new SecureRandom();

    /** 32 字节随机，URL-safe Base64（约 43 字符） */
    public String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 16 字节随机短标识（JWT jti、刷新家族 familyId） */
    public String randomId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 令牌落库摘要：SHA-256，十六进制 64 字符 */
    public String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
