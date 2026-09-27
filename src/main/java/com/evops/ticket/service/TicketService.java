package com.evops.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.order.OrderVoidRequest;
import com.evops.ticket.dto.ticket.FlowVO;
import com.evops.ticket.dto.ticket.TicketActionRequest;
import com.evops.ticket.dto.ticket.TicketDetailVO;
import com.evops.ticket.dto.ticket.TicketVO;
import com.evops.ticket.entity.SaleOrder;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.entity.TicketStatusFlow;
import com.evops.ticket.enums.FlowType;
import com.evops.ticket.enums.OrderStatus;
import com.evops.ticket.enums.TicketStatus;
import com.evops.ticket.event.TicketEventPublisher;
import com.evops.ticket.mapper.SaleOrderMapper;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.SessionSeatOccupyMapper;
import com.evops.ticket.mapper.ShowSessionMapper;
import com.evops.ticket.mapper.TicketMapper;
import com.evops.ticket.mapper.TicketStatusFlowMapper;
import com.evops.ticket.sensitive.SensitiveScene;
import com.evops.ticket.sensitive.service.SensitiveScanService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 票检索与检票状态流转。
 * 状态机：SOLD --check--> CHECKED；SOLD --refund--> REFUNDED；SOLD/CHECKED --void--> VOID。
 * 退票、作废在同事务物理删除座位占用行以释放座次，并写反向流水。
 */
@Service
public class TicketService {

    private final TicketMapper ticketMapper;
    private final SaleOrderMapper orderMapper;
    private final ShowSessionMapper sessionMapper;
    private final SeatMapper seatMapper;
    private final TicketStatusFlowMapper flowMapper;
    private final SessionSeatOccupyMapper occupyMapper;
    private final TicketFlowRecorder flowRecorder;
    private final TicketEventPublisher eventPublisher;
    private final SensitiveScanService sensitiveScanService;

    public TicketService(TicketMapper ticketMapper, SaleOrderMapper orderMapper,
                         ShowSessionMapper sessionMapper, SeatMapper seatMapper,
                         TicketStatusFlowMapper flowMapper, SessionSeatOccupyMapper occupyMapper,
                         TicketFlowRecorder flowRecorder, TicketEventPublisher eventPublisher,
                         SensitiveScanService sensitiveScanService) {
        this.ticketMapper = ticketMapper;
        this.orderMapper = orderMapper;
        this.sessionMapper = sessionMapper;
        this.seatMapper = seatMapper;
        this.flowMapper = flowMapper;
        this.occupyMapper = occupyMapper;
        this.flowRecorder = flowRecorder;
        this.eventPublisher = eventPublisher;
        this.sensitiveScanService = sensitiveScanService;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public TicketDetailVO getByTicketNo(String ticketNo) {
        Ticket ticket = requireByTicketNo(ticketNo);
        return assembleDetail(ticket);
    }

    @Transactional(readOnly = true)
    public PageVO<TicketVO> search(long page, long size, String ticketNo, Long sessionId,
                                   String status, String orderNo) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        if (ticketNo != null && !ticketNo.trim().isEmpty()) {
            wrapper.eq(Ticket::getTicketNo, ticketNo.trim());
        }
        if (sessionId != null) {
            wrapper.eq(Ticket::getSessionId, sessionId);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(Ticket::getStatus, status.trim());
        }
        if (orderNo != null && !orderNo.trim().isEmpty()) {
            LambdaQueryWrapper<SaleOrder> orderWrapper = new LambdaQueryWrapper<>();
            orderWrapper.eq(SaleOrder::getOrderNo, orderNo.trim());
            SaleOrder order = orderMapper.selectOne(orderWrapper);
            if (order == null) {
                return PageVO.empty(page, size);
            }
            wrapper.eq(Ticket::getOrderId, order.getId());
        }
        wrapper.orderByDesc(Ticket::getId);
        Page<Ticket> result = ticketMapper.selectPage(new Page<>(page, size), wrapper);
        List<TicketVO> list = result.getRecords().stream().map(this::toVO).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    // ==================== 状态流转 ====================

    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO check(String ticketNo, TicketActionRequest req) {
        Ticket ticket = requireByTicketNo(ticketNo);
        String reason = scanReason(SensitiveScene.CHECK, ticketNo, req.getReason());
        transit(ticket, TicketStatus.CHECKED, FlowType.CHECK, reason, req.getBizTime(), false);
        return assembleDetail(ticket);
    }

    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO refund(String ticketNo, TicketActionRequest req) {
        Ticket ticket = requireByTicketNo(ticketNo);
        String reason = scanReason(SensitiveScene.REFUND, ticketNo, req.getReason());
        transit(ticket, TicketStatus.REFUNDED, FlowType.REFUND, reason, req.getBizTime(), true);
        return assembleDetail(ticket);
    }

    @Transactional(rollbackFor = Exception.class)
    public TicketDetailVO voidTicket(String ticketNo, TicketActionRequest req) {
        Ticket ticket = requireByTicketNo(ticketNo);
        String reason = scanReason(SensitiveScene.VOID, ticketNo, req.getReason());
        transit(ticket, TicketStatus.VOID, FlowType.VOID, reason, req.getBizTime(), true);
        return assembleDetail(ticket);
    }

    /**
     * 检票备注过敏感词闸门：高危直接抛出（本方法尚未改任何库行，事务整体回滚），
     * 低危返回打码文本写入流水。开关关闭/平台入站豁免/扫描故障 fail-open 时原样返回。
     */
    private String scanReason(String scene, String ticketNo, String reason) {
        return sensitiveScanService.scan(scene, ticketNo, "reason", reason).getText();
    }

    /** 整单作废：单下所有票必须可作废（SOLD/CHECKED），任一不可作废整体回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public void voidOrder(Long orderId, OrderVoidRequest req) {
        SaleOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new IllegalArgumentException("售票单不存在");
        }
        if (OrderStatus.VOIDED.name().equals(order.getStatus())) {
            throw new IllegalArgumentException("售票单已作废");
        }

        LocalDateTime bizTime = req.getBizTime() != null ? req.getBizTime() : LocalDateTime.now();
        String reason = sensitiveScanService.scan(
                SensitiveScene.VOID_ORDER, "ORDER:" + orderId, "reason", req.getReason()).getText();
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Ticket::getOrderId, orderId).orderByAsc(Ticket::getId);
        List<Ticket> tickets = ticketMapper.selectList(wrapper);
        if (tickets.isEmpty()) {
            throw new IllegalArgumentException("售票单下无票记录");
        }
        for (Ticket ticket : tickets) {
            TicketStatus current = TicketStatus.valueOf(ticket.getStatus());
            if (current != TicketStatus.SOLD && current != TicketStatus.CHECKED) {
                throw new IllegalArgumentException("订单存在不可作废的票：ticketNo=" + ticket.getTicketNo());
            }
            transit(ticket, TicketStatus.VOID, FlowType.VOID, reason, bizTime, true);
        }

        order.setStatus(OrderStatus.VOIDED.name());
        order.setOperator(RequestContext.operator());
        order.setRequestId(RequestContext.requestId());
        order.setBizTime(bizTime);
        int rows = orderMapper.updateById(order);
        if (rows == 0) {
            throw new IllegalArgumentException("售票单已被他人变更，请刷新后重试");
        }
    }

    /**
     * 统一状态流转：校验状态机 -> 乐观锁更新 -> 写流水 -> 按需释放占用。
     */
    private void transit(Ticket ticket, TicketStatus target, FlowType flowType,
                         String reason, LocalDateTime bizTimeIn, boolean releaseSeat) {
        TicketStatus current = TicketStatus.valueOf(ticket.getStatus());
        if (!canTransit(current, target)) {
            throw new IllegalArgumentException(
                    "非法状态流转：" + current.name() + " -> " + target.name());
        }
        LocalDateTime bizTime = bizTimeIn != null ? bizTimeIn : LocalDateTime.now();

        ticket.setStatus(target.name());
        ticket.setOperator(RequestContext.operator());
        ticket.setRequestId(RequestContext.requestId());
        ticket.setBizTime(bizTime);
        int rows = ticketMapper.updateById(ticket);
        if (rows == 0) {
            throw new IllegalArgumentException("票状态已被他人变更，请刷新后重试");
        }

        flowRecorder.write(ticket, flowType, current, target, reason, bizTime);

        if (releaseSeat) {
            occupyMapper.release(ticket.getSessionId(), ticket.getSeatId());
        }

        // 状态变更事件：提交后通知代理平台；整单作废会逐票发布，提交后统一触发
        eventPublisher.publish(ticket.getId(), flowType, reason);
    }

    private boolean canTransit(TicketStatus from, TicketStatus to) {
        switch (to) {
            case CHECKED:
                return from == TicketStatus.SOLD;
            case REFUNDED:
                return from == TicketStatus.SOLD;
            case VOID:
                return from == TicketStatus.SOLD || from == TicketStatus.CHECKED;
            default:
                return false;
        }
    }

    // ==================== 组装 ====================

    private Ticket requireByTicketNo(String ticketNo) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Ticket::getTicketNo, ticketNo);
        Ticket ticket = ticketMapper.selectOne(wrapper);
        if (ticket == null) {
            throw new IllegalArgumentException("票不存在");
        }
        return ticket;
    }

    private TicketDetailVO assembleDetail(Ticket ticket) {
        TicketDetailVO vo = new TicketDetailVO();
        BeanUtils.copyProperties(ticket, vo);

        SaleOrder order = orderMapper.selectById(ticket.getOrderId());
        if (order != null) {
            vo.setOrderNo(order.getOrderNo());
        }
        ShowSession session = sessionMapper.selectById(ticket.getSessionId());
        if (session != null) {
            vo.setShowName(session.getShowName());
            vo.setSessionCode(session.getSessionCode());
            vo.setShowTime(session.getShowTime());
        }
        Seat seat = seatMapper.selectById(ticket.getSeatId());
        if (seat != null) {
            vo.setVenue(seat.getVenue());
            vo.setArea(seat.getArea());
            vo.setRowNo(seat.getRowNo());
            vo.setSeatNo(seat.getSeatNo());
        }

        LambdaQueryWrapper<TicketStatusFlow> flowWrapper = new LambdaQueryWrapper<>();
        flowWrapper.eq(TicketStatusFlow::getTicketId, ticket.getId())
                .orderByAsc(TicketStatusFlow::getId);
        List<FlowVO> flows = flowMapper.selectList(flowWrapper).stream().map(f -> {
            FlowVO fv = new FlowVO();
            BeanUtils.copyProperties(f, fv);
            return fv;
        }).collect(Collectors.toList());
        vo.setFlows(flows);
        return vo;
    }

    private TicketVO toVO(Ticket ticket) {
        TicketVO vo = new TicketVO();
        BeanUtils.copyProperties(ticket, vo);
        return vo;
    }
}
