package com.evops.ticket.agent;

import com.evops.common.web.RequestContext;
import com.evops.ticket.agent.config.AgentProperties;
import com.evops.ticket.agent.entity.AgentMessage;
import com.evops.ticket.agent.entity.AgentParseError;
import com.evops.ticket.agent.enums.ErrorType;
import com.evops.ticket.agent.enums.MessageDirection;
import com.evops.ticket.agent.enums.MessageStatus;
import com.evops.ticket.agent.mapper.AgentMessageMapper;
import com.evops.ticket.agent.mapper.AgentParseErrorMapper;
import com.evops.ticket.agent.protocol.AgentXmlSpec;
import com.evops.ticket.agent.service.InboundAgentService;
import com.evops.ticket.agent.service.OutboundAgentService;
import com.evops.ticket.agent.transport.AgentTransportClient;
import com.evops.ticket.agent.transport.SendResult;
import com.evops.ticket.agent.transport.TransportException;
import com.evops.ticket.dto.order.OrderDetailVO;
import com.evops.ticket.dto.order.SaleCreateRequest;
import com.evops.ticket.dto.order.SaleItem;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.ShowSessionMapper;
import com.evops.ticket.service.SaleOrderService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 代理 XML 对接关键路径与边界走查：
 * 出站（成功/网络失败退避/HTTP500/拒收/耗尽/手动重发/按票补推）、
 * 入站（检票同步/重复幂等/回声抑制/坏报文/缺字段/未知票/非法流转）、按票号归档。
 */
@SpringBootTest
class AgentIntegrationTest {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired private SaleOrderService orderService;
    @Autowired private ShowSessionMapper sessionMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private AgentMessageMapper messageMapper;
    @Autowired private AgentParseErrorMapper errorMapper;
    @Autowired private OutboundAgentService outboundService;
    @Autowired private InboundAgentService inboundService;
    @Autowired private AgentProperties properties;
    @Autowired private com.evops.ticket.mapper.TicketMapper ticketMapper;

    @MockBean
    private AgentTransportClient transport;

    /** 用桩替换定时重试任务，避免测试窗口内 cron 触发改变报文状态造成抖动。 */
    @MockBean
    private com.evops.ticket.agent.service.AgentMessageRetryJob retryJob;

    /** 同理桩掉检票通知重试任务：入站检票样例会触发通知，测试窗口内不应让 cron 真重试。 */
    @MockBean
    private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Context("box_office01", "req-" + System.nanoTime(), "/test", 0L));
        // 默认投递成功（非 ACK 体按送达处理）；各用例按需覆盖
        when(transport.send(anyString(), anyString()))
                .thenReturn(new SendResult(200, "OK"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ==================== 出站 ====================

    @Test
    void issue_success_archivedByTicketNo() {
        Fixture fx = sellOne("ST01");

        List<AgentMessage> msgs = outbound(fx.ticketNo);
        assertEquals(1, msgs.size(), "出票应产生一条出站报文");
        AgentMessage m = msgs.get(0);
        assertEquals(AgentXmlSpec.TYPE_TICKET_ISSUE, m.getMessageType());
        assertEquals(MessageDirection.OUTBOUND.name(), m.getDirection());
        assertEquals(MessageStatus.SUCCESS.name(), m.getStatus());
        assertEquals(Integer.valueOf(0), m.getRetryCount());
        assertEquals(fx.ticketNo, m.getTicketNo());
        assertNotNull(m.getOrderNo());
        // 原文留档且字段/中文按规范落盘
        assertTrue(m.getPayload().contains("<agentMessage"));
        assertTrue(m.getPayload().contains("<ticketNo>" + fx.ticketNo + "</ticketNo>"));
        assertTrue(m.getPayload().contains("测试剧目ST01"));
        assertTrue(m.getPayload().contains("<price>180.00</price>"));
        assertNotNull(m.getMessageId());
    }

    @Test
    void issue_networkFailure_retriesToExhaustion_thenManualRetrySucceeds() {
        // 前三次网络失败（首发 + 两次人工），第四次平台恢复成功
        when(transport.send(anyString(), anyString()))
                .thenThrow(new TransportException("connection refused", new RuntimeException()))
                .thenThrow(new TransportException("connection refused", new RuntimeException()))
                .thenThrow(new TransportException("connection refused", new RuntimeException()))
                .thenReturn(new SendResult(200, "OK"));

        Fixture fx = sellOne("ST02");
        AgentMessage m = outbound(fx.ticketNo).get(0);
        Long id = m.getId();
        assertEquals(MessageStatus.RETRYING.name(), m.getStatus());
        assertEquals(Integer.valueOf(1), m.getRetryCount());
        assertNotNull(m.getNextRetryTime());
        assertNotNull(m.getLastError());

        // 第二次失败：计数到 2（maxRetry=2）
        m = outboundService.manualRetry(id);
        assertEquals(MessageStatus.RETRYING.name(), m.getStatus());
        assertEquals(Integer.valueOf(2), m.getRetryCount());

        // 第三次失败：超过上限 -> FAILED
        m = outboundService.manualRetry(id);
        assertEquals(MessageStatus.FAILED.name(), m.getStatus());
        assertEquals(Integer.valueOf(2), m.getRetryCount());

        // 平台恢复后人工重发成功
        m = outboundService.manualRetry(id);
        assertEquals(MessageStatus.SUCCESS.name(), m.getStatus());
        assertEquals(Integer.valueOf(200), m.getHttpStatus());
    }

    @Test
    void issue_http500_markedRetryingWithHttpStatus() {
        when(transport.send(anyString(), anyString())).thenReturn(new SendResult(500, "boom"));
        Fixture fx = sellOne("ST03");
        AgentMessage m = outbound(fx.ticketNo).get(0);
        assertEquals(MessageStatus.RETRYING.name(), m.getStatus());
        assertEquals(Integer.valueOf(500), m.getHttpStatus());
        assertTrue(m.getLastError().contains("HTTP 500"));
    }

    @Test
    void issue_rejectedAck_terminalFailed() {
        String rejected = ackXml("R1", "REJECTED", "票号不存在于平台", null);
        when(transport.send(anyString(), anyString())).thenReturn(new SendResult(200, rejected));
        Fixture fx = sellOne("ST04");
        AgentMessage m = outbound(fx.ticketNo).get(0);
        assertEquals(MessageStatus.FAILED.name(), m.getStatus(), "平台明确拒收应终态失败");
        assertTrue(m.getLastError().contains("票号不存在于平台"));
    }

    @Test
    void issue_duplicateAckTreatedAsDelivered() {
        String dup = ackXml("R2", "DUPLICATE", "重复", null);
        when(transport.send(anyString(), anyString())).thenReturn(new SendResult(200, dup));
        Fixture fx = sellOne("ST05");
        AgentMessage m = outbound(fx.ticketNo).get(0);
        assertEquals(MessageStatus.SUCCESS.name(), m.getStatus(), "平台判重也视为已送达");
    }

    @Test
    void disabledSwitch_onlyArchivesNoSend() {
        boolean was = properties.isEnabled();
        properties.setEnabled(false);
        try {
            Fixture fx = sellOne("ST06");
            AgentMessage m = outbound(fx.ticketNo).get(0);
            assertEquals(MessageStatus.PENDING.name(), m.getStatus(), "开关关闭只留档不投递");
            // 打开后可由人工/定时任务补发
            properties.setEnabled(true);
            when(transport.send(anyString(), anyString())).thenReturn(new SendResult(200, "OK"));
            m = outboundService.manualRetry(m.getId());
            assertEquals(MessageStatus.SUCCESS.name(), m.getStatus());
        } finally {
            properties.setEnabled(was);
        }
    }

    @Test
    void resendTicket_generatesNewMessage() {
        Fixture fx = sellOne("ST07");
        AgentMessage first = outbound(fx.ticketNo).get(0);
        AgentMessage second = outboundService.resendTicket(fx.ticketNo);
        assertFalse(first.getMessageId().equals(second.getMessageId()), "补推应生成新 messageId");
        assertEquals(MessageStatus.SUCCESS.name(), second.getStatus());
        assertEquals(2, outbound(fx.ticketNo).size());
    }

    // ==================== 入站 ====================

    @Test
    void inbound_check_syncsTicket_archivesOriginal_andNoEcho() {
        Fixture fx = sellOne("ST08");

        String xml = ticketMessage("PM-CHECK-1", "TICKET_CHECK", fx.ticketNo, "CHECKED",
                "代理侧检票", LocalDateTime.now());
        InboundAgentService.InboundResult result = inboundService.receive(xml, "/api/agent/v1/messages");

        assertEquals(AgentXmlSpec.ACK_SUCCESS, result.getAck().getResult().getCode());
        // 票状态机已同步
        assertEquals("CHECKED", ticketStatus(fx.ticketNo));
        // 原文按票号归档（入站）
        List<AgentMessage> inbound = inbound(fx.ticketNo);
        assertEquals(1, inbound.size());
        assertEquals(MessageStatus.SUCCESS.name(), inbound.get(0).getStatus());
        assertEquals("PM-CHECK-1", inbound.get(0).getMessageId());
        assertTrue(inbound.get(0).getPayload().contains("代理侧检票"));
        // 回声抑制：不得再产生一条出站 CHECK
        List<AgentMessage> echo = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTicketNo, fx.ticketNo)
                .eq(AgentMessage::getDirection, MessageDirection.OUTBOUND.name())
                .eq(AgentMessage::getMessageType, AgentXmlSpec.TYPE_TICKET_CHECK));
        assertTrue(echo.isEmpty(), "入站触发的变更不得回推平台（回声）");
    }

    @Test
    void inbound_duplicateMessageId_idempotent() {
        Fixture fx = sellOne("ST09");
        String xml = ticketMessage("PM-DUP-1", "TICKET_CHECK", fx.ticketNo, "CHECKED", null, LocalDateTime.now());

        inboundService.receive(xml, "/api/agent/v1/messages");
        InboundAgentService.InboundResult second = inboundService.receive(xml, "/api/agent/v1/messages");

        assertEquals(AgentXmlSpec.ACK_DUPLICATE, second.getAck().getResult().getCode());
        assertTrue(second.isDuplicate());
        assertEquals("CHECKED", ticketStatus(fx.ticketNo));
        // 入站归档只有 1 条，未重复处理
        assertEquals(1, inbound(fx.ticketNo).size());
    }

    @Test
    void inbound_malformedXml_goesToParseErrorWithRawPayload() {
        String broken = "<agentMessage><header><messageId>PM-BAD-1</messageId>"
                + "<ticketNo>TSHBROKEN</ticketNo></header><body><ticket>";
        InboundAgentService.InboundResult result = inboundService.receive(broken, "/api/agent/v1/messages");
        assertEquals(AgentXmlSpec.ACK_REJECTED, result.getAck().getResult().getCode());

        List<AgentParseError> errors = openErrors();
        AgentParseError e = errors.stream()
                .filter(x -> "PM-BAD-1".equals(x.getMessageId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(ErrorType.PARSE_ERROR.name(), e.getErrorType());
        assertEquals("TSHBROKEN", e.getTicketNo(), "坏报文仍应兜底提取票号");
        assertEquals(broken, e.getPayload(), "坏 XML 原文必须保留");
    }

    @Test
    void inbound_missingTicketNo_validationError() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"" + AgentXmlSpec.NAMESPACE + "\">"
                + "<header><messageId>PM-NOTN</messageId><messageType>TICKET_CHECK</messageType>"
                + "<timestamp>" + LocalDateTime.now().format(F) + "</timestamp></header>"
                + "<body><ticket><status>CHECKED</status></ticket></body></agentMessage>";
        InboundAgentService.InboundResult result = inboundService.receive(xml, "/api/agent/v1/messages");
        assertEquals(AgentXmlSpec.ACK_REJECTED, result.getAck().getResult().getCode());
        assertTrue(openErrors().stream().anyMatch(e ->
                ErrorType.VALIDATION_ERROR.name().equals(e.getErrorType())
                        && "PM-NOTN".equals(e.getMessageId())));
    }

    @Test
    void inbound_unknownTicket_bizError() {
        String xml = ticketMessage("PM-UNK-1", "TICKET_CHECK", "TSH-NO-SUCH", "CHECKED", null, LocalDateTime.now());
        InboundAgentService.InboundResult result = inboundService.receive(xml, "/api/agent/v1/messages");
        assertEquals(AgentXmlSpec.ACK_REJECTED, result.getAck().getResult().getCode());
        AgentParseError e = openErrors().stream()
                .filter(x -> "PM-UNK-1".equals(x.getMessageId())).findFirst().orElseThrow(AssertionError::new);
        assertEquals(ErrorType.BIZ_ERROR.name(), e.getErrorType());
        // 报文留档为失败
        AgentMessage msg = messageMapper.selectOne(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getMessageId, "PM-UNK-1")
                .eq(AgentMessage::getDirection, MessageDirection.INBOUND.name()));
        assertEquals(MessageStatus.FAILED.name(), msg.getStatus());
    }

    @Test
    void inbound_illegalTransition_bizError() {
        Fixture fx = sellOne("ST10");
        // 先退票，再对已退票发检票 -> SOLD 已 REFUNDED，CHECK 非法
        inboundService.receive(
                ticketMessage("PM-RF-1", "TICKET_REFUND", fx.ticketNo, "REFUNDED", "代理退", LocalDateTime.now()),
                "/api/agent/v1/messages");
        assertEquals("REFUNDED", ticketStatus(fx.ticketNo));

        InboundAgentService.InboundResult check = inboundService.receive(
                ticketMessage("PM-RF-2", "TICKET_CHECK", fx.ticketNo, "CHECKED", null, LocalDateTime.now()),
                "/api/agent/v1/messages");
        assertEquals(AgentXmlSpec.ACK_REJECTED, check.getAck().getResult().getCode());
        assertTrue(openErrors().stream().anyMatch(e ->
                ErrorType.BIZ_ERROR.name().equals(e.getErrorType())
                        && "PM-RF-2".equals(e.getMessageId())));
        assertEquals("REFUNDED", ticketStatus(fx.ticketNo), "非法流转不得改动票状态");
    }

    @Test
    void inbound_unsupportedType_rejected() {
        String xml = ticketMessage("PM-TYPE-1", "TICKET_QUERY", "TSHX", "SOLD", null, LocalDateTime.now());
        InboundAgentService.InboundResult r = inboundService.receive(xml, "/api/agent/v1/messages");
        assertEquals(AgentXmlSpec.ACK_REJECTED, r.getAck().getResult().getCode());
        assertTrue(openErrors().stream().anyMatch(e ->
                ErrorType.VALIDATION_ERROR.name().equals(e.getErrorType())
                        && "PM-TYPE-1".equals(e.getMessageId())));
    }

    @Test
    void inbound_void_thenArchivedByTicketNo() {
        Fixture fx = sellOne("ST11");
        inboundService.receive(
                ticketMessage("PM-VOID-1", "TICKET_VOID", fx.ticketNo, "VOID", "代理作废", LocalDateTime.now()),
                "/api/agent/v1/messages");
        assertEquals("VOID", ticketStatus(fx.ticketNo));

        List<AgentMessage> all = messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTicketNo, fx.ticketNo).orderByAsc(AgentMessage::getId));
        // 按票号归档回查：出站出票 + 入站作废，两类原文齐备
        assertEquals(2, all.size());
        assertEquals(MessageDirection.OUTBOUND.name(), all.get(0).getDirection());
        assertEquals(MessageDirection.INBOUND.name(), all.get(1).getDirection());
    }

    // ==================== 辅助 ====================

    private List<AgentMessage> outbound(String ticketNo) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTicketNo, ticketNo)
                .eq(AgentMessage::getDirection, MessageDirection.OUTBOUND.name())
                .orderByAsc(AgentMessage::getId));
    }

    private List<AgentMessage> inbound(String ticketNo) {
        return messageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTicketNo, ticketNo)
                .eq(AgentMessage::getDirection, MessageDirection.INBOUND.name())
                .orderByAsc(AgentMessage::getId));
    }

    private List<AgentParseError> openErrors() {
        return errorMapper.selectList(new LambdaQueryWrapper<AgentParseError>()
                .eq(AgentParseError::getStatus, "OPEN"));
    }

    private String ticketStatus(String ticketNo) {
        return ticketMapper.selectOne(new LambdaQueryWrapper<com.evops.ticket.entity.Ticket>()
                .eq(com.evops.ticket.entity.Ticket::getTicketNo, ticketNo)).getStatus();
    }

    private Fixture sellOne(String sessionCode) {
        ShowSession s = new ShowSession();
        s.setShowName("测试剧目" + sessionCode);
        s.setSessionCode(sessionCode);
        s.setVenue("测试场馆");
        s.setShowTime(LocalDateTime.now().plusDays(7));
        s.setStatus("OPEN");
        s.setOperator("tester");
        sessionMapper.insert(s);

        Seat seat = new Seat();
        seat.setVenue("测试场馆");
        seat.setArea("池座");
        seat.setRowNo("Z");
        seat.setSeatNo(sessionCode);
        seat.setOperator("tester");
        seatMapper.insert(seat);

        SaleCreateRequest req = new SaleCreateRequest();
        req.setSessionId(s.getId());
        SaleItem item = new SaleItem();
        item.setSeatId(seat.getId());
        item.setPrice(new BigDecimal("180.00"));
        req.setItems(Collections.singletonList(item));

        OrderDetailVO order = orderService.create(req);
        String ticketNo = order.getTickets().get(0).getTicketNo();
        return new Fixture(s, seat, order.getOrderNo(), ticketNo);
    }

    private String ticketMessage(String messageId, String type, String ticketNo, String status,
                                 String reason, LocalDateTime bizTime) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"" + AgentXmlSpec.NAMESPACE + "\">"
                + "<header>"
                + "<messageId>" + messageId + "</messageId>"
                + "<messageType>" + type + "</messageType>"
                + "<timestamp>" + LocalDateTime.now().format(F) + "</timestamp>"
                + "<source>AGENT</source><target>VENUE</target>"
                + "</header><body><ticket>"
                + "<ticketNo>" + ticketNo + "</ticketNo>"
                + "<status>" + status + "</status>"
                + (reason == null ? "" : "<reason>" + reason + "</reason>")
                + "<operator>agent-platform</operator>"
                + "<bizTime>" + bizTime.format(F) + "</bizTime>"
                + "</ticket></body></agentMessage>";
    }

    private String ackXml(String messageId, String code, String message, String ticketNo) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentAck xmlns=\"" + AgentXmlSpec.NAMESPACE + "\">"
                + "<header><messageId>" + messageId + "</messageId>"
                + "<timestamp>" + LocalDateTime.now().format(F) + "</timestamp></header>"
                + "<result><code>" + code + "</code><message>" + message + "</message>"
                + (ticketNo == null ? "" : "<ticketNo>" + ticketNo + "</ticketNo>")
                + "</result></agentAck>";
    }

    private static class Fixture {
        final ShowSession session;
        final Seat seat;
        final String orderNo;
        final String ticketNo;

        Fixture(ShowSession session, Seat seat, String orderNo, String ticketNo) {
            this.session = session;
            this.seat = seat;
            this.orderNo = orderNo;
            this.ticketNo = ticketNo;
        }
    }
}
