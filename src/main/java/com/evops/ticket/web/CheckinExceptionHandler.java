package com.evops.ticket.web;

import com.evops.common.ApiResponse;
import com.evops.ticket.service.AppointmentConflictException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 排期冲突单独成文：HTTP 200、success=false，data 携带冲突详情与推荐空档，
 * 场务端可据 data 直接改约。优先级高于全局 IllegalArgumentException 兜底。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CheckinExceptionHandler {

    @ExceptionHandler(AppointmentConflictException.class)
    public ApiResponse<Object> handleConflict(AppointmentConflictException ex) {
        return ApiResponse.fail(ex.getMessage(), ex.getConflict());
    }
}
