package com.evops.ticket.notify.enums;

/**
 * 通知通道。INBOX 站内信（场务登录后台查收）；EMAIL 邮件（外部协作场务也能收）。
 */
public enum NotifyChannel {
    INBOX,
    EMAIL;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        for (NotifyChannel c : values()) {
            if (c.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
