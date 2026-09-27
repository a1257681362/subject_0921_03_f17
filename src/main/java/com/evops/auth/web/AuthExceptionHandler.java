package com.evops.auth.web;

import com.evops.common.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 认证异常单独成文：HTTP 状态码必须如实表达 401/400（前端据此跳登录），
 * 优先级高于全局 IllegalArgumentException -> 200/success=false 的兜底。
 * 只处理 {@link AuthException}，其余异常维持全局约定不变。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ApiResponse<Void>> handle(AuthException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiResponse.fail(ex.getMessage()));
    }
}
