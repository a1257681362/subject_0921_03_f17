package com.evops.auth.dto;

import lombok.Data;

/**
 * 登出请求体可空：只带访问令牌调用即拉黑访问令牌；
 * 同时携带刷新令牌则连该设备当前刷新令牌一并撤销。
 */
@Data
public class LogoutRequest {
    private String refreshToken;
}
