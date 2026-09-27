package com.evops.ticket.agent.protocol;

import com.evops.ticket.agent.config.AgentProperties;
import com.evops.ticket.entity.SaleOrder;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.entity.Ticket;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 出站报文装配：把票务底账实体映射为平台规范报文（字段命名与层级见 {@link XmlTicket}）。
 * messageId 由场馆生成，全局唯一，作为平台幂等键（重投安全）。
 */
@Component
public class OutboundMessageFactory {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final AgentProperties properties;

    public OutboundMessageFactory(AgentProperties properties) {
        this.properties = properties;
    }

    /** 出票通知。 */
    public AgentTicketMessage issue(Ticket ticket, SaleOrder order, ShowSession session, Seat seat) {
        return build(AgentXmlSpec.TYPE_TICKET_ISSUE, ticket, order == null ? null : order.getOrderNo(),
                session, seat, null);
    }

    /** 检票/退票/作废通知（按当前票状态由调用方指定类型）。 */
    public AgentTicketMessage statusChange(String messageType, Ticket ticket, String orderNo,
                                           ShowSession session, Seat seat, String reason) {
        return build(messageType, ticket, orderNo, session, seat, reason);
    }

    private AgentTicketMessage build(String messageType, Ticket ticket, String orderNo,
                                     ShowSession session, Seat seat, String reason) {
        AgentTicketMessage msg = new AgentTicketMessage();

        AgentTicketMessage.MessageHeader header = new AgentTicketMessage.MessageHeader();
        header.setMessageId(newMessageId());
        header.setMessageType(messageType);
        header.setTimestamp(LocalDateTime.now());
        header.setSource(properties.getVenueCode());
        header.setTarget(AgentXmlSpec.SOURCE_AGENT);
        msg.setHeader(header);

        XmlTicket xmlTicket = new XmlTicket();
        xmlTicket.setTicketNo(ticket.getTicketNo());
        xmlTicket.setOrderNo(orderNo);
        xmlTicket.setStatus(ticket.getStatus());
        xmlTicket.setPrice(ticket.getPrice());
        xmlTicket.setOperator(ticket.getOperator());
        xmlTicket.setBizTime(ticket.getBizTime() != null ? ticket.getBizTime() : LocalDateTime.now());
        xmlTicket.setReason(reason);

        if (session != null) {
            XmlTicket.XmlSession xs = new XmlTicket.XmlSession();
            xs.setShowName(session.getShowName());
            xs.setSessionCode(session.getSessionCode());
            xs.setShowTime(session.getShowTime());
            xs.setVenue(session.getVenue());
            xmlTicket.setSession(xs);
        }
        if (seat != null) {
            XmlTicket.XmlSeat xse = new XmlTicket.XmlSeat();
            xse.setArea(seat.getArea());
            xse.setRowNo(seat.getRowNo());
            xse.setSeatNo(seat.getSeatNo());
            xmlTicket.setSeat(xse);
        }

        AgentTicketMessage.MessageBody body = new AgentTicketMessage.MessageBody();
        body.setTicket(xmlTicket);
        msg.setBody(body);
        return msg;
    }

    /** MSG + 时间戳 + 8 位随机，足够去重且可读。 */
    public String newMessageId() {
        return "MSG" + LocalDateTime.now().format(ID_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    /** 应答报文号。 */
    public String newAckId() {
        return "ACK" + LocalDateTime.now().format(ID_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
