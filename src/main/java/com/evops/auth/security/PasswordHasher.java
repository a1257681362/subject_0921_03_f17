package com.evops.auth.security;

import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 口令存储：PBKDF2-HMAC-SHA256，每账号独立 16 字节盐，迭代 120000 次。
 * 库存格式 pbkdf2$iterations$base64(salt)$hex(dk)，只用 JDK 实现。
 */
@Component
public class PasswordHasher {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int DK_BITS = 256;

    private final SecureRandom random = new SecureRandom();

    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] dk = derive(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().withoutPadding().encodeToString(salt) + "$"
                + toHex(dk);
    }

    public boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = Base64.getDecoder().decode(parts[2]);
            expected = fromHex(parts[3]);
        } catch (RuntimeException e) {
            return false;
        }
        byte[] actual = derive(rawPassword, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] derive(String rawPassword, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, DK_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令派生失败", e);
        }
    }

    private byte[] fromHex(String hex) {
        char[] chars = hex.toCharArray();
        byte[] bytes = new byte[chars.length / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(chars[i * 2], 16);
            int lo = Character.digit(chars[i * 2 + 1], 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("非法十六进制");
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
