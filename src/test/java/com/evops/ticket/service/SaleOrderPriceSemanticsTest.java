package com.evops.ticket.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.order.OrderDetailVO;
import com.evops.ticket.dto.order.SaleCreateRequest;
import com.evops.ticket.dto.order.SaleItem;
import com.evops.ticket.entity.SaleOrder;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.mapper.SaleOrderMapper;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.ShowSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 票款金额空值语义锁定（2026-09 代理报文空金额按 0 入账缺陷回归）：
 *  - 空（null，对应报文 {@code <price></price>}/缺省）= 金额未知，<b>必须整单拒绝</b>，不得按 0 入账；
 *  - 显式 0 = 赠票/免票，合法，total_amount 如实为 0；
 *  - 负数拒绝；
 *  - 汇总必须等于各票金额之和，任一票金额未知都不得静默少计。
 */
@SpringBootTest
class SaleOrderPriceSemanticsTest {

    @Autowired private SaleOrderService orderService;
    @Autowired private ShowSessionMapper sessionMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private SaleOrderMapper orderMapper;

    @MockBean private com.evops.ticket.agent.service.AgentMessageRetryJob agentRetryJob;
    @MockBean private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;

    private ShowSession session;
    private Seat seatA;
    private Seat seatB;
    private String seed;

    @BeforeEach
    void setUp() {
        seed = Long.toString(System.nanoTime() % 10_000_000_000_000L);
        RequestContext.set(new RequestContext.Context(
                "box_office01", "price-sem-" + seed, "/test", 0L));

        session = new ShowSession();
        session.setShowName("空值语义剧目");
        session.setSessionCode("SEM" + seed);
        session.setVenue("语义测试场馆" + seed);
        session.setShowTime(LocalDateTime.now().plusDays(7));
        session.setStatus("OPEN");
        session.setOperator("tester");
        sessionMapper.insert(session);

        seatA = insertSeat("A");
        seatB = insertSeat("B");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private Seat insertSeat(String no) {
        Seat seat = new Seat();
        seat.setVenue(session.getVenue());
        seat.setArea("池座");
        seat.setRowNo("Z");
        seat.setSeatNo(no + "-" + seed);
        seat.setOperator("tester");
        seatMapper.insert(seat);
        return seat;
    }

    private SaleCreateRequest request(BigDecimal... prices) {
        SaleCreateRequest req = new SaleCreateRequest();
        req.setSessionId(session.getId());
        List<SaleItem> items = new ArrayList<>(prices.length);
        Seat[] seats = {seatA, seatB};
        for (int i = 0; i < prices.length; i++) {
            SaleItem item = new SaleItem();
            item.setSeatId(seats[i].getId());
            item.setPrice(prices[i]);
            items.add(item);
        }
        req.setItems(items);
        return req;
    }

    private long ordersOnSession() {
        return orderMapper.selectCount(new LambdaQueryWrapper<SaleOrder>()
                .eq(SaleOrder::getSessionId, session.getId()));
    }

    @Test
    void nullPrice_rejected_notBookedAsZero() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> orderService.create(request((BigDecimal) null)),
                "空金额（未知）必须拒绝，严禁按 0 入账");
        assertTrue(ex.getMessage().contains("金额不能为空"));
        assertEquals(0L, ordersOnSession(), "空金额拒单不得生成售票单，票款汇总不被 0 污染");
    }

    @Test
    void oneNullAmongMany_rejected_nothingBooked() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.create(request(new BigDecimal("180.00"), null)),
                "任一票金额未知都须整单拒绝");
        assertEquals(0L, ordersOnSession(), "杜绝部分按 0 少计：整单回滚");
    }

    @Test
    void explicitZero_isFreeTicket_notUnknown() {
        OrderDetailVO vo = orderService.create(request(BigDecimal.ZERO));
        BigDecimal total = orderMapper.selectById(vo.getOrderId()).getTotalAmount();
        assertEquals(0, new BigDecimal("0.00").compareTo(total),
                "显式零元票合法，订单汇总如实为 0");
        assertEquals(0, new BigDecimal("0.00").compareTo(vo.getTickets().get(0).getPrice()),
                "显式零元票票价为 0（与空值未知严格区分）");
    }

    @Test
    void negativePrice_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> orderService.create(request(new BigDecimal("-1.00"))),
                "负金额必须拒绝");
        assertEquals(0L, ordersOnSession());
    }

    @Test
    void totalAmount_equalsSumOfTicketPrices() {
        OrderDetailVO detail = orderService.create(
                request(new BigDecimal("180.50"), new BigDecimal("219.50")));
        BigDecimal total = orderMapper.selectById(detail.getOrderId()).getTotalAmount();
        assertEquals(2, detail.getTicketCount());
        assertEquals(0, new BigDecimal("400.00").compareTo(total),
                "票款汇总必须等于各票金额之和：180.50 + 219.50 = 400.00");
    }
}
