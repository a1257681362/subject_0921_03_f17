package com.evops.auth.web;

/**
 * 认证流程业务异常：携带 HTTP 状态（登录/刷新失败 401，参数问题 400）。
 */
public class AuthException extends RuntimeException {
    private final int status;

    public AuthException(int status, String message) {
        super(message);
        this.status = status;
    }

    public static AuthException unauthorized(String message) {
        return new AuthException(401, message);
    }

    public static AuthException badRequest(String message) {
        return new AuthException(400, message);
    }

    /** 已认证但角色无权访问（如非 ADMIN 维护脱敏规则）。 */
    public static AuthException forbidden(String message) {
        return new AuthException(403, message);
    }

    public int getStatus() {
        return status;
    }
}
