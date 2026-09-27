package com.evops.ticket.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.order.OrderDetailVO;
import com.evops.ticket.dto.order.OrderTicketVO;
import com.evops.ticket.dto.order.SaleCreateRequest;
import com.evops.ticket.dto.order.SaleItem;
import com.evops.ticket.entity.SaleOrder;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.SessionSeatOccupy;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.enums.FlowType;
import com.evops.ticket.enums.OrderStatus;
import com.evops.ticket.enums.TicketStatus;
import com.evops.ticket.event.TicketEventPublisher;
import com.evops.ticket.mapper.SaleOrderMapper;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.SessionSeatOccupyMapper;
import com.evops.ticket.mapper.TicketMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 售票单建档：主表与票明细在同一事务同生共死。
 */
@Service
public class SaleOrderService {

    private static final DateTimeFormatter ORDER_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SaleOrderMapper orderMapper;
    private final TicketMapper ticketMapper;
    private final SeatMapper seatMapper;
    private final SessionSeatOccupyMapper occupyMapper;
    private final ShowSessionService sessionService;
    private final TicketNoAllocator ticketNoAllocator;
    private final TicketFlowRecorder flowRecorder;
    private final TicketEventPublisher eventPublisher;

    public SaleOrderService(SaleOrderMapper orderMapper, TicketMapper ticketMapper, SeatMapper seatMapper,
                            SessionSeatOccupyMapper occupyMapper,
                            ShowSessionService sessionService, TicketNoAllocator ticketNoAllocator,
                            TicketFlowRecorder flowRecorder, TicketEventPublisher eventPublisher) {
        this.orderMapper = orderMapper;
        this.ticketMapper = ticketMapper;
        this.seatMapper = seatMapper;
        this.occupyMapper = occupyMapper;
        this.sessionService = sessionService;
        this.ticketNoAllocator = ticketNoAllocator;
        this.flowRecorder = flowRecorder;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDetailVO create(SaleCreateRequest req) {
        ShowSession session = sessionService.requireExists(req.getSessionId());
        if (!"OPEN".equals(session.getStatus())) {
            throw new IllegalArgumentException("场次已停止售票");
        }

        List<SaleItem> items = req.getItems();
        Set<Long> seatIds = new HashSet<>();
        for (SaleItem item : items) {
            if (!seatIds.add(item.getSeatId())) {
                throw new IllegalArgumentException("售票明细存在重复座位：seatId=" + item.getSeatId());
            }
            // 金额空值语义为"未知"而非 0：绝不能静默按 0 入账，否则票款汇总整段少计。
            // 控制器有 @NotNull 校验，这里给内部调用/二次加工入库再守一道。
            if (item.getPrice() == null) {
                throw new IllegalArgumentException(
                        "票款金额不能为空：票号对应金额未提供（空值不等于 0），请核实后补报");
            }
            if (item.getPrice().signum() < 0) {
                throw new IllegalArgumentException("票款金额不能为负数：seatId=" + item.getSeatId());
            }
        }

        List<Seat> seats = new ArrayList<>(items.size());
        for (Long seatId : seatIds) {
            Seat seat = seatMapper.selectById(seatId);
            if (seat == null) {
                throw new IllegalArgumentException("座位不存在：seatId=" + seatId);
            }
            if (!session.getVenue().equals(seat.getVenue())) {
                throw new IllegalArgumentException("座位不属于该场次场馆：seatId=" + seatId);
            }
            seats.add(seat);
        }

        LocalDateTime bizTime = req.getBizTime() != null ? req.getBizTime() : LocalDateTime.now();
        String operator = RequestContext.operator();
        String requestId = RequestContext.requestId();

        // 票号在锁内按场次连续分配
        List<String> ticketNos = ticketNoAllocator.allocate(session, items.size());

        SaleOrder order = new SaleOrder();
        order.setOrderNo(generateOrderNo());
        order.setSessionId(session.getId());
        order.setTicketCount(items.size());
        order.setTotalAmount(items.stream()
                .map(SaleItem::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        order.setStatus(OrderStatus.ISSUED.name());
        order.setOperator(operator);
        order.setRequestId(requestId);
        order.setBizTime(bizTime);
        orderMapper.insert(order);

        List<Ticket> tickets = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            SaleItem item = items.get(i);
            Ticket ticket = new Ticket();
            ticket.setTicketNo(ticketNos.get(i));
            ticket.setOrderId(order.getId());
            ticket.setSessionId(session.getId());
            ticket.setSeatId(item.getSeatId());
            ticket.setPrice(item.getPrice());
            ticket.setStatus(TicketStatus.SOLD.name());
            ticket.setOperator(operator);
            ticket.setRequestId(requestId);
            ticket.setBizTime(bizTime);
            ticketMapper.insert(ticket);
            tickets.add(ticket);

            // 占用座位：撞复合主键即同场次已售，整单回滚
            SessionSeatOccupy occupy = new SessionSeatOccupy();
            occupy.setSessionId(session.getId());
            occupy.setSeatId(item.getSeatId());
            occupy.setTicketId(ticket.getId());
            occupy.setOrderId(order.getId());
            try {
                occupyMapper.insert(occupy);
            } catch (DuplicateKeyException e) {
                throw new IllegalArgumentException("座位在该场次已售：seatId=" + item.getSeatId());
            }

            // ISSUE 正向流水，自身即追溯链起点
            flowRecorder.write(ticket, FlowType.ISSUE, null, TicketStatus.SOLD, null, bizTime);

            // 出票事件：提交后通知代理平台（回滚不通知），失败不影响售票
            eventPublisher.publish(ticket.getId(), FlowType.ISSUE, null);
        }

        return toDetailVO(order, session, tickets);
    }

    private String generateOrderNo() {
        return "SO" + LocalDateTime.now().format(ORDER_NO_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
    }

    @Transactional(readOnly = true)
    public OrderDetailVO getDetail(Long id) {
        SaleOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw new IllegalArgumentException("售票单不存在");
        }
        ShowSession session = sessionService.requireExists(order.getSessionId());
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Ticket> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(Ticket::getOrderId, id).orderByAsc(Ticket::getId);
        List<Ticket> tickets = ticketMapper.selectList(wrapper);
        return toDetailVO(order, session, tickets);
    }

    public static OrderDetailVO toDetailVO(SaleOrder order, ShowSession session, List<Ticket> tickets) {
        OrderDetailVO vo = new OrderDetailVO();
        vo.setOrderId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setSessionId(order.getSessionId());
        vo.setShowName(session == null ? null : session.getShowName());
        vo.setTicketCount(order.getTicketCount());
        vo.setStatus(order.getStatus());
        vo.setOperator(order.getOperator());
        vo.setRequestId(order.getRequestId());
        vo.setBizTime(order.getBizTime());
        vo.setVersion(order.getVersion());
        List<OrderTicketVO> ticketVOs = new ArrayList<>(tickets.size());
        for (Ticket t : tickets) {
            OrderTicketVO tv = new OrderTicketVO();
            BeanUtils.copyProperties(t, tv);
            ticketVOs.add(tv);
        }
        vo.setTickets(ticketVOs);
        return vo;
    }
}
