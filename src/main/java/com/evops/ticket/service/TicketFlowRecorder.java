package com.evops.ticket.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.entity.TicketStatusFlow;
import com.evops.ticket.enums.FlowType;
import com.evops.ticket.enums.TicketStatus;
import com.evops.ticket.mapper.TicketStatusFlowMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 票状态流水记录器：所有状态变更统一经此写流水，反向记录关联本票 ISSUE 流水。
 */
@Component
public class TicketFlowRecorder {

    private final TicketStatusFlowMapper flowMapper;

    public TicketFlowRecorder(TicketStatusFlowMapper flowMapper) {
        this.flowMapper = flowMapper;
    }

    public TicketStatusFlow write(Ticket ticket, FlowType type, TicketStatus from,
                                  TicketStatus to, String reason, LocalDateTime bizTime) {
        TicketStatusFlow flow = new TicketStatusFlow();
        flow.setTicketId(ticket.getId());
        flow.setOrderId(ticket.getOrderId());
        flow.setSessionId(ticket.getSessionId());
        flow.setRecordType(type.name());
        flow.setReverseFlag(type.reverseFlag());
        if (type != FlowType.ISSUE) {
            flow.setOriginRecordId(findIssueFlowId(ticket.getId()));
        }
        flow.setFromStatus(from == null ? null : from.name());
        flow.setToStatus(to == null ? null : to.name());
        flow.setReason(reason);
        flow.setOperator(RequestContext.operator());
        flow.setRequestId(RequestContext.requestId());
        flow.setBizTime(bizTime != null ? bizTime : LocalDateTime.now());
        flowMapper.insert(flow);
        return flow;
    }

    private Long findIssueFlowId(Long ticketId) {
        LambdaQueryWrapper<TicketStatusFlow> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TicketStatusFlow::getTicketId, ticketId)
                .eq(TicketStatusFlow::getRecordType, FlowType.ISSUE.name())
                .orderByAsc(TicketStatusFlow::getId)
                .last("LIMIT 1");
        TicketStatusFlow issue = flowMapper.selectOne(wrapper);
        if (issue == null) {
            throw new IllegalStateException("票缺少出票流水：ticketId=" + ticketId);
        }
        return issue.getId();
    }
}
