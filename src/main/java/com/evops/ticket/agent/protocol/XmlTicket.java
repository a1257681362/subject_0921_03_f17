package com.evops.ticket.agent.protocol;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 票报文业务体，层级与字段命名按代理平台规范：
 * <pre>{@code
 * <ticket>
 *   <ticketNo>TSH002000000001</ticketNo>
 *   <orderNo>SO...</orderNo>
 *   <status>SOLD</status>
 *   <price>180.00</price>
 *   <operator>box_office01</operator>
 *   <bizTime>2026-09-23 19:30:00</bizTime>
 *   <reason></reason>
 *   <session> <showName/> <sessionCode/> <showTime/> <venue/> </session>
 *   <seat>    <area/> <rowNo/> <seatNo/> </seat>
 * </ticket>
 * }</pre>
 */
public class XmlTicket {

    @JacksonXmlProperty(localName = "ticketNo", namespace = AgentXmlSpec.NAMESPACE)
    private String ticketNo;

    @JacksonXmlProperty(localName = "orderNo", namespace = AgentXmlSpec.NAMESPACE)
    private String orderNo;

    /** 平台票状态口径与本系统一致：SOLD / CHECKED / REFUNDED / VOID */
    @JacksonXmlProperty(localName = "status", namespace = AgentXmlSpec.NAMESPACE)
    private String status;

    /**
     * 票款金额。空标签/缺省为 {@code null}（平台未提供，未知），<b>不是 0</b>；
     * 显式 {@code <price>0</price>} 才是零元（赠票/免票）。语义由
     * {@link EmptyAwareBigDecimalDeserializer} 与测试用例锁定。
     */
    @JacksonXmlProperty(localName = "price", namespace = AgentXmlSpec.NAMESPACE)
    @JsonDeserialize(using = EmptyAwareBigDecimalDeserializer.class)
    private BigDecimal price;

    @JacksonXmlProperty(localName = "operator", namespace = AgentXmlSpec.NAMESPACE)
    private String operator;

    @JacksonXmlProperty(localName = "bizTime", namespace = AgentXmlSpec.NAMESPACE)
    private LocalDateTime bizTime;

    /** 退票/作废原因，出票可空 */
    @JacksonXmlProperty(localName = "reason", namespace = AgentXmlSpec.NAMESPACE)
    private String reason;

    @JacksonXmlProperty(localName = "session", namespace = AgentXmlSpec.NAMESPACE)
    private XmlSession session;

    @JacksonXmlProperty(localName = "seat", namespace = AgentXmlSpec.NAMESPACE)
    private XmlSeat seat;

    public String getTicketNo() { return ticketNo; }
    public void setTicketNo(String ticketNo) { this.ticketNo = ticketNo; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
    public LocalDateTime getBizTime() { return bizTime; }
    public void setBizTime(LocalDateTime bizTime) { this.bizTime = bizTime; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public XmlSession getSession() { return session; }
    public void setSession(XmlSession session) { this.session = session; }
    public XmlSeat getSeat() { return seat; }
    public void setSeat(XmlSeat seat) { this.seat = seat; }

    /** 场次信息块。 */
    public static class XmlSession {
        @JacksonXmlProperty(localName = "showName", namespace = AgentXmlSpec.NAMESPACE)
        private String showName;

        @JacksonXmlProperty(localName = "sessionCode", namespace = AgentXmlSpec.NAMESPACE)
        private String sessionCode;

        @JacksonXmlProperty(localName = "showTime", namespace = AgentXmlSpec.NAMESPACE)
        private LocalDateTime showTime;

        @JacksonXmlProperty(localName = "venue", namespace = AgentXmlSpec.NAMESPACE)
        private String venue;

        public String getShowName() { return showName; }
        public void setShowName(String showName) { this.showName = showName; }
        public String getSessionCode() { return sessionCode; }
        public void setSessionCode(String sessionCode) { this.sessionCode = sessionCode; }
        public LocalDateTime getShowTime() { return showTime; }
        public void setShowTime(LocalDateTime showTime) { this.showTime = showTime; }
        public String getVenue() { return venue; }
        public void setVenue(String venue) { this.venue = venue; }
    }

    /** 座位信息块。 */
    public static class XmlSeat {
        @JacksonXmlProperty(localName = "area", namespace = AgentXmlSpec.NAMESPACE)
        private String area;

        @JacksonXmlProperty(localName = "rowNo", namespace = AgentXmlSpec.NAMESPACE)
        private String rowNo;

        @JacksonXmlProperty(localName = "seatNo", namespace = AgentXmlSpec.NAMESPACE)
        private String seatNo;

        public String getArea() { return area; }
        public void setArea(String area) { this.area = area; }
        public String getRowNo() { return rowNo; }
        public void setRowNo(String rowNo) { this.rowNo = rowNo; }
        public String getSeatNo() { return seatNo; }
        public void setSeatNo(String seatNo) { this.seatNo = seatNo; }
    }
}
