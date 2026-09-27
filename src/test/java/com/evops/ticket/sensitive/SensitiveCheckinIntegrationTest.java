package com.evops.ticket.sensitive;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.web.RequestContext;
import com.evops.ticket.agent.service.AgentInboundGuard;
import com.evops.ticket.dto.checkin.AppointmentCreateRequest;
import com.evops.ticket.dto.order.OrderDetailVO;
import com.evops.ticket.dto.order.OrderVoidRequest;
import com.evops.ticket.dto.order.SaleCreateRequest;
import com.evops.ticket.dto.order.SaleItem;
import com.evops.ticket.dto.ticket.TicketActionRequest;
import com.evops.ticket.entity.CheckinAppointment;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.entity.TicketStatusFlow;
import com.evops.ticket.enums.AppointmentStatus;
import com.evops.ticket.mapper.CheckinAppointmentMapper;
import com.evops.ticket.mapper.SaleOrderMapper;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.ShowSessionMapper;
import com.evops.ticket.mapper.TicketMapper;
import com.evops.ticket.mapper.TicketStatusFlowMapper;
import com.evops.ticket.sensitive.entity.SensitiveHitLog;
import com.evops.ticket.sensitive.mapper.SensitiveHitLogMapper;
import com.evops.ticket.sensitive.service.SensitiveScanService;
import com.evops.ticket.sensitive.service.SensitiveWordHolder;
import com.evops.ticket.sensitive.config.SensitiveProperties;
import com.evops.ticket.agent.transport.AgentTransportClient;
import com.evops.ticket.agent.transport.SendResult;
import com.evops.ticket.notify.channel.ChannelSendResult;
import com.evops.ticket.notify.channel.EmailChannelSender;
import com.evops.ticket.service.CheckinAppointmentService;
import com.evops.ticket.service.SaleOrderService;
import com.evops.ticket.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 检票备注敏感词扫描全链路走查：
 * 1) 高危命中：检票/退票/整单作废/取消排期一律拦截，库行不动，命中留痕（原文+码点位置）独立事务保住；
 * 2) 低危命中：放行业务，落库文本打码，留痕 MASKED；
 * 3) 全角/插缝干扰归一后仍命中，位置区间准确覆盖干扰符；
 * 4) 干净文本无留痕；
 * 5) 总开关关闭立即切回旧逻辑；
 * 6) fail-open：扫描引擎故障按原文放行；fail-open=false 时故障拦截；
 * 7) 代理平台入站报文默认豁免，打开开关后同样拦截。
 *
 * 票/排期均现场自建，避免与其它测试类争抢样本 SOLD 票。
 */
@SpringBootTest
class SensitiveCheckinIntegrationTest {

    @Autowired private TicketService ticketService;
    @Autowired private SaleOrderService orderService;
    @Autowired private ShowSessionMapper sessionMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private TicketMapper ticketMapper;
    @Autowired private TicketStatusFlowMapper flowMapper;
    @Autowired private CheckinAppointmentService appointmentService;
    @Autowired private CheckinAppointmentMapper appointmentMapper;
    @Autowired private SaleOrderMapper saleOrderMapper;
    @Autowired private SensitiveScanService scanService;
    @Autowired private SensitiveProperties properties;
    @Autowired private SensitiveHitLogMapper hitLogMapper;

    /** 词库持有器用 Spy：fail-open 用例需要人为制造扫描引擎异常 */
    @SpyBean private SensitiveWordHolder wordHolder;

    @MockBean private AgentTransportClient agentTransport;
    @MockBean private com.evops.ticket.agent.service.AgentMessageRetryJob agentRetryJob;
    @MockBean private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;
    @MockBean private EmailChannelSender emailChannelSender;

    private boolean enabledBackup;
    private boolean failOpenBackup;
    private boolean scanAgentBackup;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Context(
                "ticket_check01", "sens-test-" + System.nanoTime(), "/test", 0L));
        when(agentTransport.send(any(), any())).thenReturn(new SendResult(200, "OK"));
        when(emailChannelSender.channel()).thenReturn("EMAIL");
        when(emailChannelSender.send(any())).thenAnswer(inv ->
                ChannelSendResult.ok(200, "MID-" + System.nanoTime()));
        wordHolder.reload(); // 防御：不依赖 ApplicationReadyEvent 时机
        enabledBackup = properties.isEnabled();
        failOpenBackup = properties.isFailOpen();
        scanAgentBackup = properties.isScanAgentInbound();
        properties.setEnabled(true);
        properties.setFailOpen(true);
        properties.setScanAgentInbound(false);
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(enabledBackup);
        properties.setFailOpen(failOpenBackup);
        properties.setScanAgentInbound(scanAgentBackup);
        RequestContext.clear();
    }

    // ==================== 高危拦截 ====================

    @Test
    void check_highRisk_blocked_nothingPersisted_logKeepsOriginalAndPosition() {
        Fixture fx = sellOne("SS01");
        String reason = "场外黄牛带进场";

        SensitiveBlockException ex = assertThrows(SensitiveBlockException.class,
                () -> ticketService.check(fx.getTicketNo(), action(reason)));
        assertTrue(ex.getMessage().contains("黄牛"));

        // 票状态未动、没有新增 CHECK 流水
        assertEquals("SOLD", ticketStatus(fx.getTicketNo()));
        assertNull(latestCheckFlow(fx.getTicketNo()));

        // 留痕：原文、词、级别、处置、码点位置齐备（黄牛 在「场外」之后 [2,4)）
        List<SensitiveHitLog> logs = hitsOf("CHECK", fx.getTicketNo());
        assertEquals(1, logs.size());
        SensitiveHitLog log = logs.get(0);
        assertEquals("黄牛", log.getWord());
        assertEquals("HIGH", log.getLevel());
        assertEquals("BLOCKED", log.getAction());
        assertEquals("reason", log.getFieldName());
        assertEquals(2, log.getStartCp());
        assertEquals(4, log.getEndCp());
        assertEquals("黄牛", log.getMatchedText());
        assertEquals(reason, log.getOriginalText());
        assertNull(log.getMaskedText(), "拦截不产生打码文本");
    }

    @Test
    void check_highRiskWithInsertedSpace_normalizedAndSpanCoversSpace() {
        Fixture fx = sellOne("SS02");
        // 半角空格插缝：归一后命中，区间覆盖空格 [0,3)
        String reason = "黄 牛叫卖";
        assertThrows(SensitiveBlockException.class,
                () -> ticketService.check(fx.getTicketNo(), action(reason)));
        assertEquals("SOLD", ticketStatus(fx.getTicketNo()));

        SensitiveHitLog log = hitsOf("CHECK", fx.getTicketNo()).get(0);
        assertEquals(0, log.getStartCp());
        assertEquals(3, log.getEndCp());
        assertEquals("黄 牛", log.getMatchedText());
    }

    @Test
    void check_highRiskFullWidthLatin_caseFoldedAndMasked() {
        Fixture fx = sellOne("SS03");
        // 全角大写 Ｖ 经 NFKC->V->小写 v，命中低危词「加v」：业务放行、片段打码
        String reason = "请加Ｖ联系";
        ticketService.check(fx.getTicketNo(), action(reason));
        assertEquals("CHECKED", ticketStatus(fx.getTicketNo()));
        SensitiveHitLog log = hitsOf("CHECK", fx.getTicketNo()).get(0);
        assertEquals("加v", log.getWord());
        assertEquals("LOW", log.getLevel());
        assertEquals(1, log.getStartCp());
        assertEquals(3, log.getEndCp());
        assertEquals("加Ｖ", log.getMatchedText());
        assertEquals("请**联系", log.getMaskedText());
    }

    @Test
    void refund_highRisk_blocked_ticketStaysSold() {
        Fixture fx = sellOne("SS04");
        assertThrows(SensitiveBlockException.class,
                () -> ticketService.refund(fx.getTicketNo(), action("发现诈骗短信")));
        assertEquals("SOLD", ticketStatus(fx.getTicketNo()));
        assertEquals("诈骗", hitsOf("REFUND", fx.getTicketNo()).get(0).getWord());
    }

    @Test
    void voidOrder_highRisk_blocked_orderAndTicketsUntouched() {
        Fixture fx = sellTwo("SS05");
        OrderVoidRequest req = new OrderVoidRequest();
        req.setReason("整单涉及倒票");
        assertThrows(SensitiveBlockException.class, () -> ticketService.voidOrder(fx.orderId, req));

        assertEquals("ISSUED", orderStatus(fx.orderId));
        for (String ticketNo : fx.ticketNos) {
            assertEquals("SOLD", ticketStatus(ticketNo));
        }
        List<SensitiveHitLog> logs = hitsOf("VOID_ORDER", "ORDER:" + fx.orderId);
        assertEquals(1, logs.size());
        assertEquals("倒票", logs.get(0).getWord());
    }

    @Test
    void cancelAppointment_highRisk_blocked_appointmentStaysActive() {
        Long apptId = createAppointment("SS06");
        assertThrows(SensitiveBlockException.class,
                () -> appointmentService.cancel(apptId, "黄牛代约取消", LocalDateTime.now()));
        CheckinAppointment appt = appointmentMapper.selectById(apptId);
        assertEquals(AppointmentStatus.ACTIVE.name(), appt.getStatus());
        assertNull(appt.getCancelReason());
    }

    // ==================== 低危打码放行 ====================

    @Test
    void check_lowRisk_maskedPersisted_logActionMaskedWithPositions() {
        Fixture fx = sellOne("SS07");
        // 词库含「加微」：扫描到 [1,3) 即终结命中，信字不属任何词，仅「加微」两码点打码
        String reason = "请加微信领票";
        ticketService.check(fx.getTicketNo(), action(reason));

        // 流水里落的是打码文本（加微 [1,3) 逐码点替换为 *）
        TicketStatusFlow flow = latestCheckFlow(fx.getTicketNo());
        assertEquals("请**信领票", flow.getReason());

        SensitiveHitLog log = hitsOf("CHECK", fx.getTicketNo()).get(0);
        assertEquals("加微", log.getWord());
        assertEquals("LOW", log.getLevel());
        assertEquals("MASKED", log.getAction());
        assertEquals(1, log.getStartCp());
        assertEquals(3, log.getEndCp());
        assertEquals("加微", log.getMatchedText());
        assertEquals(reason, log.getOriginalText());
        assertEquals("请**信领票", log.getMaskedText());
    }

    @Test
    void check_twoNonOverlappingLowWords_bothMasked() {
        Fixture fx = sellOne("SS07B");
        // 两个不相邻低危词「代购」「广告」，分别打码
        String reason = "代购小广告";
        ticketService.check(fx.getTicketNo(), action(reason));
        assertEquals("**小**", latestCheckFlow(fx.getTicketNo()).getReason());
        List<SensitiveHitLog> logs = hitsOf("CHECK", fx.getTicketNo());
        assertEquals(2, logs.size());
        assertEquals("代购", logs.get(0).getWord());
        assertEquals("广告", logs.get(1).getWord());
    }

    @Test
    void cancelAppointment_lowRisk_maskedAndCancelled() {
        Long apptId = createAppointment("SS08");
        appointmentService.cancel(apptId, "加v改约", LocalDateTime.now());
        CheckinAppointment appt = appointmentMapper.selectById(apptId);
        assertEquals(AppointmentStatus.CANCELLED.name(), appt.getStatus());
        assertEquals("**改约", appt.getCancelReason());
        SensitiveHitLog log = hitsOf("APPT_CANCEL", appt.getAppointmentNo()).get(0);
        assertEquals("加v", log.getWord());
        assertEquals("MASKED", log.getAction());
    }

    @Test
    void cleanReason_passesWithoutAnyHitLog() {
        Fixture fx = sellOne("SS09");
        ticketService.check(fx.getTicketNo(), action("入场检票"));
        assertEquals("CHECKED", ticketStatus(fx.getTicketNo()));
        assertTrue(hitsOf("CHECK", fx.getTicketNo()).isEmpty());
    }

    // ==================== 回退预案 ====================

    @Test
    void disabledSwitch_scanSkipped_originalReasonPersisted() {
        properties.setEnabled(false); // 预案：立即切回旧逻辑
        Fixture fx = sellOne("SS10");
        String reason = "黄牛诈骗都不管";
        ticketService.check(fx.getTicketNo(), action(reason));

        assertEquals("CHECKED", ticketStatus(fx.getTicketNo()));
        assertEquals(reason, latestCheckFlow(fx.getTicketNo()).getReason(), "旧逻辑原文落库");
        assertTrue(hitsOf("CHECK", fx.getTicketNo()).isEmpty(), "关闭后不留痕");
    }

    @Test
    void failOpen_engineError_passesOriginal() {
        Fixture fx = sellOne("SS11");
        doThrow(new RuntimeException("模拟 DFA 故障")).when(wordHolder).current();

        properties.setFailOpen(true);
        String reason = "黄牛";
        ticketService.check(fx.getTicketNo(), action(reason)); // 不抛异常
        assertEquals("CHECKED", ticketStatus(fx.getTicketNo()));
        assertEquals(reason, latestCheckFlow(fx.getTicketNo()).getReason());
    }

    @Test
    void failOpenClosed_engineError_blocks() {
        Fixture fx = sellOne("SS12");
        doThrow(new RuntimeException("模拟 DFA 故障")).when(wordHolder).current();
        properties.setFailOpen(false);
        assertThrows(SensitiveBlockException.class,
                () -> ticketService.check(fx.getTicketNo(), action("随便写点什么")));
        assertEquals("SOLD", ticketStatus(fx.getTicketNo()));
    }

    @Test
    void agentInbound_exemptByDefault_blockedWhenOptedIn() {
        // 模拟平台 -> 场馆入站线程
        AgentInboundGuard.enter();
        try {
            SensitiveScanService.ScanResult result = scanService.scan(
                    SensitiveScene.CHECK, "AGENT-T", "reason", "黄牛进场");
            assertEquals("黄牛进场", result.getText());
            assertTrue(hitsOf("CHECK", "AGENT-T").isEmpty());
        } finally {
            AgentInboundGuard.exit();
        }

        properties.setScanAgentInbound(true);
        AgentInboundGuard.enter();
        try {
            assertThrows(SensitiveBlockException.class, () -> scanService.scan(
                    SensitiveScene.CHECK, "AGENT-T", "reason", "黄牛进场"));
        } finally {
            AgentInboundGuard.exit();
        }
    }

    // ==================== 辅助 ====================

    private TicketActionRequest action(String reason) {
        TicketActionRequest req = new TicketActionRequest();
        req.setReason(reason);
        return req;
    }

    private String ticketStatus(String ticketNo) {
        return ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketNo, ticketNo).last("LIMIT 1")).getStatus();
    }

    private String orderStatus(Long orderId) {
        return saleOrderMapper.selectById(orderId).getStatus();
    }

    private TicketStatusFlow latestCheckFlow(String ticketNo) {
        Ticket ticket = ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketNo, ticketNo).last("LIMIT 1"));
        List<TicketStatusFlow> flows = flowMapper.selectList(new LambdaQueryWrapper<TicketStatusFlow>()
                .eq(TicketStatusFlow::getTicketId, ticket.getId())
                .eq(TicketStatusFlow::getRecordType, "CHECK")
                .orderByDesc(TicketStatusFlow::getId));
        return flows.isEmpty() ? null : flows.get(0);
    }

    private List<SensitiveHitLog> hitsOf(String scene, String bizRef) {
        return hitLogMapper.selectList(new LambdaQueryWrapper<SensitiveHitLog>()
                .eq(SensitiveHitLog::getScene, scene)
                .eq(SensitiveHitLog::getBizRef, bizRef)
                .orderByAsc(SensitiveHitLog::getId));
    }

    private Long createAppointment(String code) {
        Seat seat = newSeat(code);
        seatMapper.insert(seat);
        AppointmentCreateRequest req = new AppointmentCreateRequest();
        req.setSeatId(seat.getId());
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        req.setStartTime(start);
        req.setEndTime(start.plusMinutes(30));
        req.setVisitorName("张三");
        req.setContact("13800000000");
        return appointmentService.create(req).getId();
    }

    private Fixture sellOne(String code) {
        return sellN(code, 1);
    }

    private Fixture sellTwo(String code) {
        return sellN(code, 2);
    }

    private Fixture sellN(String code, int n) {
        ShowSession session = new ShowSession();
        session.setShowName("敏感词测试剧目" + code);
        session.setSessionCode(code);
        session.setVenue("测试场馆");
        session.setShowTime(LocalDateTime.now().plusDays(7));
        session.setStatus("OPEN");
        session.setOperator("tester");
        sessionMapper.insert(session);

        SaleCreateRequest req = new SaleCreateRequest();
        req.setSessionId(session.getId());
        List<SaleItem> items = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            Seat seat = newSeat(code + "-" + i);
            seatMapper.insert(seat);
            SaleItem item = new SaleItem();
            item.setSeatId(seat.getId());
            item.setPrice(new BigDecimal("180.00"));
            items.add(item);
        }
        req.setItems(items);

        OrderDetailVO order = orderService.create(req);
        List<String> ticketNos = new ArrayList<>();
        order.getTickets().forEach(t -> ticketNos.add(t.getTicketNo()));
        return new Fixture(order.getOrderNo(), order.getOrderId(), ticketNos);
    }

    private Seat newSeat(String code) {
        Seat seat = new Seat();
        seat.setVenue("测试场馆");
        seat.setArea("池座");
        seat.setRowNo("Z");
        seat.setSeatNo(code);
        seat.setOperator("tester");
        return seat;
    }

    private static final class Fixture {
        final String orderNo;
        final Long orderId;
        final List<String> ticketNos;

        Fixture(String orderNo, Long orderId, List<String> ticketNos) {
            this.orderNo = orderNo;
            this.orderId = orderId;
            this.ticketNos = ticketNos;
        }

        String getTicketNo() {
            return ticketNos.get(0);
        }
    }
}
