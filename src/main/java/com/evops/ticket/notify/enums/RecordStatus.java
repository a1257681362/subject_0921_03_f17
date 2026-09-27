package com.evops.ticket.notify.enums;

/**
 * 单条发送记录状态。
 * PENDING 待发 / SUCCESS 成功 / RETRYING 退避重试中 / FAILED 重试2次耗尽 / SKIPPED 收件人不具备该通道条件。
 */
public enum RecordStatus {
    PENDING,
    SUCCESS,
    RETRYING,
    FAILED,
    SKIPPED
}
