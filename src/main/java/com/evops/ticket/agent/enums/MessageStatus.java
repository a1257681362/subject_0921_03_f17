package com.evops.ticket.agent.enums;

/**
 * 报文归档状态。
 * 出站：PENDING -> RETRYING（退避中）-> SUCCESS；重试耗尽 -> FAILED（可人工再触发）。
 * 入站：处理成功 SUCCESS；重复 messageId IGNORED。
 */
public enum MessageStatus {
    PENDING,
    RETRYING,
    SUCCESS,
    FAILED,
    IGNORED
}
