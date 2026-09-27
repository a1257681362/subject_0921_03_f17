package com.evops.ticket.enums;

/**
 * 检票排期状态：ACTIVE 有效占用；CANCELLED 已取消（区间立即释放，可再约）。
 */
public enum AppointmentStatus {
    ACTIVE,
    CANCELLED
}
