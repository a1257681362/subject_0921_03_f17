package com.evops.ticket.notify.enums;

/**
 * 单条发送记录状态。
 * PENDING 待发 / SENDING 投递中（已被某执行方原子占用，租约写在 next_retry_time）/
 * SUCCESS 成功 / RETRYING 退避等待下次续投 / FAILED 重试2次耗尽 / SKIPPED 收件人不具备该通道条件。
 */
public enum RecordStatus {
    PENDING,
    SENDING,
    SUCCESS,
    RETRYING,
    FAILED,
    SKIPPED
}
