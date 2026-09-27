package com.evops.ticket.notify.service;

import com.evops.ticket.entity.SaleOrder;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.entity.Ticket;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把票/订单/场次/座位二次加工成模板可用的变量集合（现场文案需要的全量上下文）。
 * 这正是"现在的数据要二次加工才能用"的落点：底账各表字段在这里汇成一张变量表。
 */
public final class NotifyVarsBuilder {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private NotifyVarsBuilder() {
    }

    public static Map<String, String> build(Ticket ticket, SaleOrder order, ShowSession session,
                                            Seat seat, String operator, String reason,
                                            LocalDateTime eventTime) {
        Map<String, String> vars = new LinkedHashMap<>();
        put(vars, "ticketNo", ticket.getTicketNo());
        put(vars, "orderNo", order == null ? null : order.getOrderNo());
        put(vars, "showName", session == null ? null : session.getShowName());
        put(vars, "sessionCode", session == null ? null : session.getSessionCode());
        put(vars, "venue", seat == null ? null : seat.getVenue());
        put(vars, "area", seat == null ? null : seat.getArea());
        put(vars, "rowNo", seat == null ? null : seat.getRowNo());
        put(vars, "seatNo", seat == null ? null : seat.getSeatNo());
        put(vars, "price", ticket.getPrice() == null ? null : ticket.getPrice().toPlainString());
        // 检票时间优先取票上业务时间（检票落账时间），其次事件时间
        LocalDateTime checkTime = ticket.getBizTime() != null ? ticket.getBizTime() : eventTime;
        put(vars, "checkTime", checkTime == null ? null : TIME_FMT.format(checkTime));
        put(vars, "showTime", session == null || session.getShowTime() == null
                ? null : TIME_FMT.format(session.getShowTime()));
        put(vars, "operator", operator);
        put(vars, "reason", reason);
        return vars;
    }

    private static void put(Map<String, String> vars, String key, String value) {
        vars.put(key, value == null ? "" : value);
    }
}
