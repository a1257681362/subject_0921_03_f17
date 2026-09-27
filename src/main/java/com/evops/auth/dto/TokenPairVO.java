package com.evops.auth.dto;

import lombok.Data;

/**
 * 双令牌响应。访问令牌放 Authorization: Bearer；刷新令牌仅在登录/刷新时返回，
 * 客户端应安全存储（HttpOnly Cookie 或安全存储区），不随业务请求上送。
 */
@Data
public class TokenPairVO {
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    /** 访问令牌剩余秒数（短） */
    private long accessTokenExpiresIn;
    /** 刷新令牌剩余秒数 */
    private long refreshTokenExpiresIn;
    private AdminProfileVO user;
}
