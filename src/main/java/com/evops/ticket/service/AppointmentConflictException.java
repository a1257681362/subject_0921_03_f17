package com.evops.ticket.service;

/**
 * 预约冲突：携带冲突详情与推荐空档，由 Web 层转成 success=false 响应（非 5xx）。
 */
public class AppointmentConflictException extends RuntimeException {

    private final transient Object conflict;

    public AppointmentConflictException(String message, Object conflict) {
        super(message);
        this.conflict = conflict;
    }

    public Object getConflict() {
        return conflict;
    }
}
