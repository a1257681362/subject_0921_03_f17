package com.evops.ticket.agent.web;

import com.evops.common.web.RequestContext;
import com.evops.ticket.agent.protocol.AgentXmlSpec;
import com.evops.ticket.agent.transport.AgentTransportClient;
import com.evops.ticket.agent.transport.SendResult;
import com.evops.ticket.dto.order.OrderDetailVO;
import com.evops.ticket.dto.order.SaleCreateRequest;
import com.evops.ticket.dto.order.SaleItem;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.ShowSessionMapper;
import com.evops.ticket.service.SaleOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 真实 HTTP 层走查：平台匿名回调 XML 收发、Shiro 放行、Content-Type 协商；
 * 管理端端点在无访问令牌时 401。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentCallbackWebTest {

    private static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @LocalServerPort
    private int port;

    @Autowired private TestRestTemplate rest;
    @Autowired private SaleOrderService orderService;
    @Autowired private ShowSessionMapper sessionMapper;
    @Autowired private SeatMapper seatMapper;

    @MockBean
    private AgentTransportClient transport;
    @MockBean
    private com.evops.ticket.agent.service.AgentMessageRetryJob retryJob;
    /** 入站检票样例会触发检票通知，桩掉其重试 cron 避免测试窗口内真重试。 */
    @MockBean
    private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;

    /** 同一内存库跨方法累积，场次短码/座位需唯一。 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private String ticketNo;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Context("box_office01", "webreq-" + System.nanoTime(), "/test", 0L));
        when(transport.send(anyString(), anyString())).thenReturn(new SendResult(200, "OK"));

        int n = SEQ.incrementAndGet();
        ShowSession s = new ShowSession();
        s.setShowName("联调剧目");
        s.setSessionCode(String.format("WB%05d", n));
        s.setVenue("联调场馆");
        s.setShowTime(LocalDateTime.now().plusDays(7));
        s.setStatus("OPEN");
        s.setOperator("tester");
        sessionMapper.insert(s);

        Seat seat = new Seat();
        seat.setVenue("联调场馆");
        seat.setArea("池座");
        seat.setRowNo("W");
        seat.setSeatNo(String.format("%02d", n));
        seat.setOperator("tester");
        seatMapper.insert(seat);

        SaleCreateRequest req = new SaleCreateRequest();
        req.setSessionId(s.getId());
        SaleItem item = new SaleItem();
        item.setSeatId(seat.getId());
        item.setPrice(new BigDecimal("200.00"));
        req.setItems(Collections.singletonList(item));
        OrderDetailVO order = orderService.create(req);
        ticketNo = order.getTickets().get(0).getTicketNo();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void callback_validXml_returnsAckXmlAnonymously() {
        String xml = ticketMessage("WB-CHECK-1", "TICKET_CHECK", ticketNo, "CHECKED", null);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> entity = new HttpEntity<>(xml, headers);

        ResponseEntity<String> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/agent/v1/messages", entity, String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        MediaType contentType = resp.getHeaders().getContentType();
        assertTrue(contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_XML),
                "应回 application/xml，实际=" + contentType);
        String body = resp.getBody();
        assertTrue(body != null && body.contains("<agentAck") && body.contains("<code>SUCCESS</code>"),
                "应回 SUCCESS ACK，实际=" + body);
        assertTrue(body.contains("WB-CHECK-1"), "ACK 应回填 correlationId");
    }

    @Test
    void callback_brokenXml_still200RejectedAck() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_XML);
        HttpEntity<String> entity = new HttpEntity<>("<agentMessage><header><messageId>WB-BAD</messageId>", headers);

        ResponseEntity<String> resp = rest.postForEntity(
                "http://localhost:" + port + "/api/agent/v1/messages", entity, String.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode(), "坏报文也走 ACK，HTTP 恒 200");
        assertTrue(resp.getBody().contains("<code>REJECTED</code>"));
    }

    @Test
    void adminEndpoint_withoutToken_isUnauthorized() {
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + port + "/api/ticket/agent/messages", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(), "管理端必须持访问令牌");
    }

    private String ticketMessage(String messageId, String type, String ticketNo, String status, String reason) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"" + AgentXmlSpec.NAMESPACE + "\">"
                + "<header><messageId>" + messageId + "</messageId>"
                + "<messageType>" + type + "</messageType>"
                + "<timestamp>" + LocalDateTime.now().format(F) + "</timestamp>"
                + "<source>AGENT</source><target>VENUE</target></header>"
                + "<body><ticket><ticketNo>" + ticketNo + "</ticketNo>"
                + "<status>" + status + "</status>"
                + (reason == null ? "" : "<reason>" + reason + "</reason>")
                + "<operator>agent-platform</operator>"
                + "<bizTime>" + LocalDateTime.now().format(F) + "</bizTime>"
                + "</ticket></body></agentMessage>";
    }
}
