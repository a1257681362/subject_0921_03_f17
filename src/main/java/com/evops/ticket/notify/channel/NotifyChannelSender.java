package com.evops.ticket.notify.channel;

import com.evops.ticket.notify.entity.NotifyRecord;

/**
 * 通知通道发送器：INBOX 站内信、EMAIL 邮件各一个实现。
 * 发送器只负责投递，不感知模板/重试；重试与状态落库由 NotifySendService 统一裁定。
 */
public interface NotifyChannelSender {

    /** 通道码：{@link com.evops.ticket.notify.enums.NotifyChannel} */
    String channel();

    ChannelSendResult send(NotifyRecord record);
}
