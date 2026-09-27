package com.evops.ticket.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.ticket.notify.dto.InboxMessageVO;
import com.evops.ticket.notify.entity.InboxMessage;
import com.evops.ticket.notify.mapper.InboxMessageMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 场务站内信箱：场务只能按本人登录工号查收，标记已读幂等。
 */
@Service
public class InboxService {

    private final InboxMessageMapper inboxMapper;

    public InboxService(InboxMessageMapper inboxMapper) {
        this.inboxMapper = inboxMapper;
    }

    /** 分页查本人信箱；readFlag 0 未读 / 1 已读，null 全部。 */
    @Transactional(readOnly = true)
    public PageVO<InboxMessageVO> pageMine(String staffNo, long page, long size, Integer readFlag) {
        LambdaQueryWrapper<InboxMessage> w = new LambdaQueryWrapper<InboxMessage>()
                .eq(InboxMessage::getStaffNo, staffNo);
        if (readFlag != null) {
            w.eq(InboxMessage::getReadFlag, readFlag);
        }
        w.orderByDesc(InboxMessage::getId);
        Page<InboxMessage> result = inboxMapper.selectPage(new Page<>(page, size), w);
        List<InboxMessageVO> list = result.getRecords().stream().map(m -> {
            InboxMessageVO vo = new InboxMessageVO();
            BeanUtils.copyProperties(m, vo);
            return vo;
        }).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    @Transactional(readOnly = true)
    public long countUnread(String staffNo) {
        Long cnt = inboxMapper.selectCount(new LambdaQueryWrapper<InboxMessage>()
                .eq(InboxMessage::getStaffNo, staffNo)
                .eq(InboxMessage::getReadFlag, 0));
        return cnt == null ? 0 : cnt;
    }

    @Transactional(readOnly = true)
    public InboxMessageVO getMine(String staffNo, Long id) {
        InboxMessage m = requireMine(staffNo, id);
        InboxMessageVO vo = new InboxMessageVO();
        BeanUtils.copyProperties(m, vo);
        return vo;
    }

    /** 标记已读（幂等：重复标记不改变首次阅读时间）。 */
    @Transactional(rollbackFor = Exception.class)
    public void markRead(String staffNo, Long id) {
        InboxMessage m = requireMine(staffNo, id);
        if (m.getReadFlag() != null && m.getReadFlag() == 1) {
            return;
        }
        m.setReadFlag(1);
        m.setReadTime(LocalDateTime.now());
        inboxMapper.updateById(m);
    }

    private InboxMessage requireMine(String staffNo, Long id) {
        InboxMessage m = inboxMapper.selectById(id);
        if (m == null || !staffNo.equals(m.getStaffNo())) {
            // 不暴露他人消息是否存在
            throw new IllegalArgumentException("站内信不存在：id=" + id);
        }
        return m;
    }
}
