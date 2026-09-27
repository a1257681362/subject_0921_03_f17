package com.evops.auth.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.evops.auth.config.AuthProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT(HS256) 签发与校验，仅依赖 JDK + Jackson（与既有技术栈一致，不引第三方 JWT 库）。
 *
 * 访问令牌声明：iss / sub(用户id) / username / tv(密码版本) / typ=access / jti / iat / exp。
 * 吊销通道：登出·改密按 jti 进黑名单；改密使全体旧令牌 tv 不匹配立即失效。
 */
@Component
public class JwtService {

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();
    private static final String HEADER_SEGMENT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";

    private final AuthProperties props;
    private final ObjectMapper objectMapper;

    public JwtService(AuthProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public String issueAccess(long userId, String username, int tokenVersion, String jti) {
        long now = epochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", props.getIssuer());
        claims.put("sub", String.valueOf(userId));
        claims.put("username", username);
        claims.put("tv", tokenVersion);
        claims.put("typ", "access");
        claims.put("jti", jti);
        claims.put("iat", now);
        claims.put("nbf", now);
        claims.put("exp", now + props.getAccessTokenTtlSeconds());
        return sign(claims);
    }

    public long accessTtlSeconds() {
        return props.getAccessTokenTtlSeconds();
    }

    /**
     * 只 base64 解码访问令牌负载取 exp，不校验签名/有效期。
     * 仅供签发侧（AuthService 登记刷新令牌配对信息）解析本服务刚签发的令牌，
     * 外部入站令牌一律走 {@link #verify}。
     */
    public long peekExpEpoch(String justIssuedToken) {
        String[] parts = justIssuedToken.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("令牌格式非法");
        }
        try {
            Map<?, ?> claims = objectMapper.readValue(B64URL_DEC.decode(parts[1]), Map.class);
            return ((Number) claims.get("exp")).longValue();
        } catch (Exception e) {
            throw new IllegalArgumentException("令牌声明非法");
        }
    }

    /**
     * 校验签名与结构并取声明；过期、伪造、类型不符一律抛 IllegalArgumentException，
     * 由上层统一转 401，错误描述不区分细节以防探测。
     */
    public JwtClaims verify(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("令牌格式非法");
        }
        if (!MessageDigest.isEqual(HEADER_SEGMENT.getBytes(StandardCharsets.US_ASCII),
                parts[0].getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("令牌头不受支持");
        }
        byte[] expectedSig = hmac(parts[0] + "." + parts[1]);
        byte[] actualSig;
        try {
            actualSig = B64URL_DEC.decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("令牌签名非法");
        }
        if (!MessageDigest.isEqual(expectedSig, actualSig)) {
            throw new IllegalArgumentException("令牌签名校验失败");
        }

        Map<?, ?> claims;
        try {
            claims = objectMapper.readValue(B64URL_DEC.decode(parts[1]), Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("令牌声明非法");
        }
        if (!"access".equals(claims.get("typ"))) {
            throw new IllegalArgumentException("令牌类型非法");
        }
        Object expObj = claims.get("exp");
        if (!(expObj instanceof Number) || ((Number) expObj).longValue() <= epochSecond()) {
            throw new IllegalArgumentException("令牌已过期");
        }
        try {
            JwtClaims parsed = new JwtClaims();
            parsed.setUserId(Long.parseLong(String.valueOf(claims.get("sub"))));
            parsed.setUsername((String) claims.get("username"));
            parsed.setTokenVersion(((Number) claims.get("tv")).intValue());
            parsed.setJti((String) claims.get("jti"));
            parsed.setExp(((Number) expObj).longValue());
            return parsed;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("令牌声明缺失");
        }
    }

    private String sign(Map<String, Object> claims) {
        String body;
        try {
            body = B64URL.encodeToString(objectMapper.writeValueAsBytes(claims));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JWT 序列化失败", e);
        }
        String signingInput = HEADER_SEGMENT + "." + body;
        return signingInput + "." + B64URL.encodeToString(hmac(signingInput));
    }

    private byte[] hmac(String input) {
        try {
            Mac clone = Mac.getInstance("HmacSHA256");
            clone.init(new SecretKeySpec(props.secretBytes(), "HmacSHA256"));
            return clone.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    protected long epochSecond() {
        return System.currentTimeMillis() / 1000L;
    }
}
