package com.evops.ticket.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.common.web.RequestContext;
import com.evops.ticket.agent.dto.AgentMessageDetailVO;
import com.evops.ticket.agent.dto.AgentMessageVO;
import com.evops.ticket.agent.dto.AgentParseErrorVO;
import com.evops.ticket.agent.entity.AgentMessage;
import com.evops.ticket.agent.entity.AgentParseError;
import com.evops.ticket.agent.enums.ErrorStatus;
import com.evops.ticket.agent.mapper.AgentMessageMapper;
import com.evops.ticket.agent.mapper.AgentParseErrorMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 报文归档与异常清单的回查/管理：
 * 报文按票号归档可回查原文；异常清单可分页检索、按票号过滤、人工标记处理。
 */
@Service
public class AgentMessageAdminService {

    private final AgentMessageMapper messageMapper;
    private final AgentParseErrorMapper errorMapper;
    private final AgentMessageStore store;

    public AgentMessageAdminService(AgentMessageMapper messageMapper,
                                    AgentParseErrorMapper errorMapper, AgentMessageStore store) {
        this.messageMapper = messageMapper;
        this.errorMapper = errorMapper;
        this.store = store;
    }

    /** 报文分页：可按方向/类型/状态/票号过滤。 */
    public PageVO<AgentMessageVO> pageMessages(long page, long size, String direction,
                                               String messageType, String status, String ticketNo) {
        LambdaQueryWrapper<AgentMessage> w = new LambdaQueryWrapper<>();
        if (hasText(direction)) {
            w.eq(AgentMessage::getDirection, direction.trim());
        }
        if (hasText(messageType)) {
            w.eq(AgentMessage::getMessageType, messageType.trim());
        }
        if (hasText(status)) {
            w.eq(AgentMessage::getStatus, status.trim());
        }
        if (hasText(ticketNo)) {
            w.eq(AgentMessage::getTicketNo, ticketNo.trim());
        }
        w.orderByDesc(AgentMessage::getId);
        Page<AgentMessage> result = messageMapper.selectPage(new Page<>(page, size), w);
        List<AgentMessageVO> list = result.getRecords().stream()
                .map(this::toMessageVO).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    /** 按票号归档回查：该票全部收发报文（含原文），按时间正序。 */
    public List<AgentMessageDetailVO> listByTicketNo(String ticketNo) {
        if (!hasText(ticketNo)) {
            throw new IllegalArgumentException("票号不能为空");
        }
        LambdaQueryWrapper<AgentMessage> w = new LambdaQueryWrapper<>();
        w.eq(AgentMessage::getTicketNo, ticketNo.trim()).orderByAsc(AgentMessage::getId);
        return messageMapper.selectList(w).stream()
                .map(this::toDetailVO).collect(Collectors.toList());
    }

    public AgentMessageDetailVO getMessage(Long id) {
        AgentMessage m = store.getById(id);
        if (m == null) {
            throw new IllegalArgumentException("报文不存在：id=" + id);
        }
        return toDetailVO(m);
    }

    /** 出站操作后回显最新状态；行不存在返回 null。 */
    public AgentMessageDetailVO toDetailOrNull(AgentMessage m) {
        return m == null ? null : toDetailVO(m);
    }

    /** 异常清单分页：可按状态/类型/票号过滤，默认只看 OPEN。 */
    public PageVO<AgentParseErrorVO> pageErrors(long page, long size, String status,
                                                String errorType, String ticketNo) {
        LambdaQueryWrapper<AgentParseError> w = new LambdaQueryWrapper<>();
        w.eq(AgentParseError::getStatus, hasText(status) ? status.trim() : ErrorStatus.OPEN.name());
        if (hasText(errorType)) {
            w.eq(AgentParseError::getErrorType, errorType.trim());
        }
        if (hasText(ticketNo)) {
            w.eq(AgentParseError::getTicketNo, ticketNo.trim());
        }
        w.orderByDesc(AgentParseError::getId);
        Page<AgentParseError> result = errorMapper.selectPage(new Page<>(page, size), w);
        List<AgentParseErrorVO> list = result.getRecords().stream()
                .map(this::toErrorVO).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    public AgentParseError getErrorEntity(Long id) {
        AgentParseError error = errorMapper.selectById(id);
        if (error == null) {
            throw new IllegalArgumentException("异常记录不存在：id=" + id);
        }
        return error;
    }

    /** 取异常关联的原始报文（坏 XML 也在此）。 */
    public String getErrorPayload(Long id) {
        return getErrorEntity(id).getPayload();
    }

    public void resolveError(Long id, String status, String remark) {
        String target;
        if (!hasText(status)) {
            target = ErrorStatus.RESOLVED.name();
        } else {
            target = status.trim();
            if (!ErrorStatus.RESOLVED.name().equals(target) && !ErrorStatus.IGNORED.name().equals(target)) {
                throw new IllegalArgumentException("处理状态仅支持 RESOLVED / IGNORED");
            }
        }
        store.resolveError(id, target, remark, RequestContext.operator());
    }

    private AgentMessageVO toMessageVO(AgentMessage m) {
        AgentMessageVO vo = new AgentMessageVO();
        BeanUtils.copyProperties(m, vo);
        return vo;
    }

    private AgentMessageDetailVO toDetailVO(AgentMessage m) {
        AgentMessageDetailVO vo = new AgentMessageDetailVO();
        BeanUtils.copyProperties(m, vo);
        return vo;
    }

    private AgentParseErrorVO toErrorVO(AgentParseError e) {
        AgentParseErrorVO vo = new AgentParseErrorVO();
        BeanUtils.copyProperties(e, vo);
        return vo;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
