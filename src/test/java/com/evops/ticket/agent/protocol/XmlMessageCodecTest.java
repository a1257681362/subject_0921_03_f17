package com.evops.ticket.agent.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XML 双向映射边界走查：字段命名/层级、平台时间格式、中文编码、未知字段容错、坏报文、ACK。
 */
class XmlMessageCodecTest {

    private final XmlMessageCodec codec = new XmlMessageCodec();

    private AgentTicketMessage sample() {
        AgentTicketMessage msg = new AgentTicketMessage();
        AgentTicketMessage.MessageHeader h = new AgentTicketMessage.MessageHeader();
        h.setMessageId("MSG20260923193000ABCD1234");
        h.setMessageType(AgentXmlSpec.TYPE_TICKET_ISSUE);
        h.setTimestamp(LocalDateTime.of(2026, 9, 23, 19, 30, 0));
        h.setSource("VENUE");
        h.setTarget("AGENT");
        msg.setHeader(h);

        XmlTicket t = new XmlTicket();
        t.setTicketNo("TSH002000000001");
        t.setOrderNo("SO20260923190000A1B2");
        t.setStatus("SOLD");
        t.setPrice(new BigDecimal("180.00"));
        t.setOperator("box_office01");
        t.setBizTime(LocalDateTime.of(2026, 9, 23, 19, 30, 0));

        XmlTicket.XmlSession s = new XmlTicket.XmlSession();
        s.setShowName("天鹅湖·芭蕾经典");
        s.setSessionCode("SH002");
        s.setVenue("滨江大剧院");
        s.setShowTime(LocalDateTime.of(2026, 10, 1, 19, 30, 0));
        t.setSession(s);

        XmlTicket.XmlSeat seat = new XmlTicket.XmlSeat();
        seat.setArea("一层池座");
        seat.setRowNo("A");
        seat.setSeatNo("08");
        t.setSeat(seat);

        AgentTicketMessage.MessageBody b = new AgentTicketMessage.MessageBody();
        b.setTicket(t);
        msg.setBody(b);
        return msg;
    }

    @Test
    void serialize_followsSpecNamingHierarchyAndCharset() {
        String xml = codec.toXml(sample());
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), "应有 UTF-8 声明");
        assertTrue(xml.contains("<agentMessage"), "根元素应为 agentMessage");
        assertTrue(xml.contains("ticket/v1"), "应带平台命名空间");
        assertTrue(xml.contains("<header>") && xml.contains("<messageId>MSG20260923193000ABCD1234</messageId>"));
        assertTrue(xml.contains("<messageType>TICKET_ISSUE</messageType>"));
        assertTrue(xml.contains("<timestamp>2026-09-23 19:30:00</timestamp>"), "时间应为平台规范格式");
        assertTrue(xml.contains("<body>") && xml.contains("<ticket>") && xml.contains("<ticketNo>TSH002000000001</ticketNo>"));
        assertTrue(xml.contains("<price>180.00</price>"));
        // 层级：session/seat 嵌套在 ticket 内
        assertTrue(xml.contains("<session>") && xml.contains("<showName>天鹅湖·芭蕾经典</showName>")
                && xml.contains("<sessionCode>SH002</sessionCode>"));
        assertTrue(xml.contains("<seat>") && xml.contains("<area>一层池座</area>")
                && xml.contains("<rowNo>A</rowNo>") && xml.contains("<seatNo>08</seatNo>"));
        // UTF-8 编码后中文可还原
        String reparsed = new String(codec.toXmlBytes(sample()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(reparsed.contains("滨江大剧院"));
    }

    @Test
    void roundTrip_preservesAllFields() throws Exception {
        AgentTicketMessage parsed = codec.parse(codec.toXml(sample()));
        assertEquals("MSG20260923193000ABCD1234", parsed.getHeader().getMessageId());
        assertEquals("TICKET_ISSUE", parsed.getHeader().getMessageType());
        assertEquals(LocalDateTime.of(2026, 9, 23, 19, 30, 0), parsed.getHeader().getTimestamp());
        XmlTicket t = parsed.getBody().getTicket();
        assertEquals("TSH002000000001", t.getTicketNo());
        assertEquals(0, new BigDecimal("180.00").compareTo(t.getPrice()));
        assertEquals("天鹅湖·芭蕾经典", t.getSession().getShowName());
        assertEquals("SH002", t.getSession().getSessionCode());
        assertEquals(LocalDateTime.of(2026, 10, 1, 19, 30, 0), t.getSession().getShowTime());
        assertEquals("一层池座", t.getSeat().getArea());
        assertEquals("08", t.getSeat().getSeatNo());
    }

    @Test
    void parse_acceptsIsoAndPlatformDatetime() throws Exception {
        String iso = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"http://schema.evops.com/ticket/v1\">"
                + "<header><messageId>m1</messageId><messageType>TICKET_CHECK</messageType>"
                + "<timestamp>2026-09-23T19:30:00</timestamp></header>"
                + "<body><ticket><ticketNo>T1</ticketNo><bizTime>2026-09-23 19:30:00</bizTime></ticket></body>"
                + "</agentMessage>";
        AgentTicketMessage msg = codec.parse(iso);
        assertEquals(LocalDateTime.of(2026, 9, 23, 19, 30, 0), msg.getHeader().getTimestamp());
        assertEquals(LocalDateTime.of(2026, 9, 23, 19, 30, 0), msg.getBody().getTicket().getBizTime());
    }

    @Test
    void parse_ignoresUnknownFields() throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"http://schema.evops.com/ticket/v1\">"
                + "<header><messageId>m2</messageId><messageType>TICKET_CHECK</messageType>"
                + "<futureField>x</futureField></header>"
                + "<body><ticket><ticketNo>T1</ticketNo><extra>123</extra></ticket></body>"
                + "</agentMessage>";
        AgentTicketMessage msg = codec.parse(xml);
        assertEquals("m2", msg.getHeader().getMessageId());
        assertEquals("T1", msg.getBody().getTicket().getTicketNo());
    }

    @Test
    void parse_malformedThrows() {
        assertThrows(JsonProcessingException.class, () ->
                codec.parse("<agentMessage><header><messageId>broken"));
        assertThrows(JsonProcessingException.class, () -> codec.parse("not xml at all"));
        assertThrows(JsonProcessingException.class, () -> codec.parse(""));
    }

    @Test
    void parse_badDatetimeThrows() {
        String xml = "<agentMessage xmlns=\"http://schema.evops.com/ticket/v1\">"
                + "<header><messageId>m3</messageId><messageType>TICKET_CHECK</messageType>"
                + "<timestamp>2026/09/23 19:30</timestamp></header>"
                + "<body><ticket><ticketNo>T1</ticketNo></ticket></body></agentMessage>";
        assertThrows(JsonProcessingException.class, () -> codec.parse(xml));
    }

    @Test
    void parse_emptyPrice_isUnknownNotZero() throws Exception {
        // <price></price>：空标签语义为"平台未提供金额（未知）"，必须是 null，绝不是 0
        String emptyTag = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"" + AgentXmlSpec.NAMESPACE + "\">"
                + "<header><messageId>m-empty</messageId><messageType>TICKET_CHECK</messageType>"
                + "<timestamp>2026-09-23 19:30:00</timestamp></header>"
                + "<body><ticket><ticketNo>T1</ticketNo><price></price></ticket></body></agentMessage>";
        assertNull(codec.parse(emptyTag).getBody().getTicket().getPrice(),
                "空 price 标签必须解析为 null（金额未知），不得当 0");

        // 纯空白 <price>   </price> 同为未知
        String blankTag = emptyTag.replace("<price></price>", "<price>   </price>")
                .replace("m-empty", "m-blank");
        assertNull(codec.parse(blankTag).getBody().getTicket().getPrice(),
                "纯空白 price 标签必须解析为 null（金额未知）");

        // 整个节点缺省同样为未知
        String missing = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"" + AgentXmlSpec.NAMESPACE + "\">"
                + "<header><messageId>m-missing</messageId><messageType>TICKET_CHECK</messageType>"
                + "<timestamp>2026-09-23 19:30:00</timestamp></header>"
                + "<body><ticket><ticketNo>T1</ticketNo></ticket></body></agentMessage>";
        assertNull(codec.parse(missing).getBody().getTicket().getPrice(),
                "缺省 price 节点必须解析为 null（金额未知）");
    }

    @Test
    void parse_zeroPrice_isExplicitZero() throws Exception {
        // <price>0</price>：平台明确给零（赠票/免票），必须保留为 ZERO，与空值严格区分
        String zero = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"" + AgentXmlSpec.NAMESPACE + "\">"
                + "<header><messageId>m-zero</messageId><messageType>TICKET_CHECK</messageType>"
                + "<timestamp>2026-09-23 19:30:00</timestamp></header>"
                + "<body><ticket><ticketNo>T1</ticketNo><price>0</price></ticket></body></agentMessage>";
        BigDecimal price = codec.parse(zero).getBody().getTicket().getPrice();
        assertNotNull(price, "显式 0 不能被当成空值");
        assertEquals(0, BigDecimal.ZERO.compareTo(price));
    }

    @Test
    void parse_nonNumericPrice_throwsAsBadPayload() {
        // 非数字金额：坏报文，不得静默吞成 0 或 null
        String bad = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<agentMessage xmlns=\"" + AgentXmlSpec.NAMESPACE + "\">"
                + "<header><messageId>m-bad</messageId><messageType>TICKET_CHECK</messageType>"
                + "<timestamp>2026-09-23 19:30:00</timestamp></header>"
                + "<body><ticket><ticketNo>T1</ticketNo><price>abc</price></ticket></body></agentMessage>";
        assertThrows(JsonProcessingException.class, () -> codec.parse(bad));
    }

    @Test
    void ack_roundTrip() throws Exception {
        AgentAck ack = new AgentAck();
        AgentAck.AckHeader h = new AgentAck.AckHeader();
        h.setMessageId("ACK1");
        h.setCorrelationId("MSG1");
        h.setTimestamp(LocalDateTime.of(2026, 9, 23, 19, 30, 0));
        AgentAck.AckResult r = new AgentAck.AckResult();
        r.setCode("REJECTED");
        r.setMessage("非法状态流转");
        r.setTicketNo("T1");
        ack.setHeader(h);
        ack.setResult(r);

        String xml = codec.toXml(ack);
        assertTrue(xml.contains("<agentAck") && xml.contains("<code>REJECTED</code>"));
        assertTrue(xml.contains("<correlationId>MSG1</correlationId>"));

        AgentAck back = codec.parseAck(xml);
        assertEquals("REJECTED", back.getResult().getCode());
        assertEquals("非法状态流转", back.getResult().getMessage());
        assertEquals("MSG1", back.getHeader().getCorrelationId());
    }

    @Test
    void extractTag_recoversKeysFromBrokenXml() {
        String broken = "<garbage><messageId>MSG999</messageId><ticketNo>TSH999</ticketNo></gar";
        assertEquals("MSG999", XmlMessageCodec.extractTag(broken, "messageId"));
        assertEquals("TSH999", XmlMessageCodec.extractTag(broken, "ticketNo"));
        assertNull(XmlMessageCodec.extractTag(broken, "missing"));
        assertNull(XmlMessageCodec.extractTag(null, "messageId"));
    }
}
