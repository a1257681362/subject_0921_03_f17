package com.evops.ticket.agent.service;

import com.evops.ticket.agent.entity.AgentMessage;
import com.evops.ticket.agent.entity.AgentParseError;
import com.evops.ticket.agent.mapper.AgentMessageMapper;
import com.evops.ticket.agent.mapper.AgentParseErrorMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 报文/异常清单持久化门面。所有写操作走 REQUIRES_NEW 独立事务：
 * 即便业务处理抛错回滚，收发原文与异常登记也必须留档可回查（与认证重放连坐同口径）。
 */
@Component
public class AgentMessageStore {

    private final AgentMessageMapper messageMapper;
    private final AgentParseErrorMapper errorMapper;

    public AgentMessageStore(AgentMessageMapper messageMapper, AgentParseErrorMapper errorMapper) {
        this.messageMapper = messageMapper;
        this.errorMapper = errorMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public AgentMessage insert(AgentMessage message) {
        message.setCreateTime(LocalDateTime.now());
        message.setUpdateTime(LocalDateTime.now());
        messageMapper.insert(message);
        return message;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void updateSendResult(Long id, String status, Integer httpStatus, int retryCount,
                                 LocalDateTime nextRetryTime, String lastError) {
        AgentMessage m = messageMapper.selectById(id);
        if (m == null) {
            return;
        }
        m.setStatus(status);
        m.setHttpStatus(httpStatus);
        m.setRetryCount(retryCount);
        m.setNextRetryTime(nextRetryTime);
        m.setLastError(lastError);
        m.setUpdateTime(LocalDateTime.now());
        messageMapper.updateById(m);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordError(AgentParseError error) {
        error.setCreateTime(LocalDateTime.now());
        errorMapper.insert(error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void resolveError(Long id, String status, String remark, String operator) {
        AgentParseError error = errorMapper.selectById(id);
        if (error == null) {
            throw new IllegalArgumentException("异常记录不存在：id=" + id);
        }
        error.setStatus(status);
        error.setResolveRemark(remark);
        error.setResolveTime(LocalDateTime.now());
        error.setOperator(operator);
        errorMapper.updateById(error);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markStatus(Long id, String status) {
        AgentMessage m = messageMapper.selectById(id);
        if (m == null) {
            return;
        }
        m.setStatus(status);
        m.setUpdateTime(LocalDateTime.now());
        messageMapper.updateById(m);
    }

    @Transactional(readOnly = true)
    public AgentMessage findByMessageId(String messageId, String direction) {
        return messageMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getMessageId, messageId)
                .eq(AgentMessage::getDirection, direction)
                .last("LIMIT 1"));
    }

    @Transactional(readOnly = true)
    public List<AgentMessage> dueOutbound(int limit) {
        return messageMapper.selectDueOutbound(LocalDateTime.now(), limit);
    }

    @Transactional(readOnly = true)
    public AgentMessage getById(Long id) {
        return messageMapper.selectById(id);
    }

    public AgentMessageMapper messageMapper() {
        return messageMapper;
    }

    public AgentParseErrorMapper errorMapper() {
        return errorMapper;
    }
}
