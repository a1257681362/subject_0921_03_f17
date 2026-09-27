package com.evops.ticket.notify.channel;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.ticket.notify.entity.InboxMessage;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.enums.NotifyChannel;
import com.evops.ticket.notify.mapper.InboxMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 站内信通道：渲染好的标题/正文直接落场务站内信箱（本地库，不经网络）。
 * 按发送记录 id 幂等（select 预检 + uk_inbox_record 唯一约束兜底）：
 * 重试不会产生重复站内信。独立事务提交，外层发送状态更新失败也不回收已送达的信。
 */
@Component
public class InboxChannelSender implements NotifyChannelSender {

    private static final Logger log = LoggerFactory.getLogger(InboxChannelSender.class);

    private final InboxMessageMapper inboxMapper;

    public InboxChannelSender(InboxMessageMapper inboxMapper) {
        this.inboxMapper = inboxMapper;
    }

    @Override
    public String channel() {
        return NotifyChannel.INBOX.name();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ChannelSendResult send(NotifyRecord record) {
        try {
            Long exists = inboxMapper.selectCount(new LambdaQueryWrapper<InboxMessage>()
                    .eq(InboxMessage::getRecordId, record.getId()));
            if (exists != null && exists > 0) {
                // 重试到达：信箱行此前已写入，按送达成功处理，不重复投递
                return ChannelSendResult.ok(200, String.valueOf(record.getId()));
            }
            InboxMessage message = new InboxMessage();
            message.setStaffNo(record.getRecipientAddress());
            message.setRecipientName(record.getRecipientName());
            message.setTitle(record.getTitleRendered());
            message.setContent(record.getContentRendered());
            message.setCategory(record.getEventCode());
            message.setBizKey(record.getTicketNo());
            message.setReadFlag(0);
            message.setRecordId(record.getId());
            message.setCreateTime(LocalDateTime.now());
            inboxMapper.insert(message);
            return ChannelSendResult.ok(200, String.valueOf(message.getId()));
        } catch (DuplicateKeyException e) {
            // 并发重试撞唯一键：另一线程已送达
            return ChannelSendResult.ok(200, String.valueOf(record.getId()));
        } catch (Exception e) {
            log.warn("站内信投递失败 recordId={} staffNo={} err={}",
                    record.getId(), record.getRecipientAddress(), e.getMessage());
            return ChannelSendResult.fail(null, "站内信落库失败：" + e.getMessage());
        }
    }
}
