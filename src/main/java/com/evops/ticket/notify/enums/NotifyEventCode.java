package com.evops.ticket.notify.enums;

/**
 * 通知事件码。当前开演保障只放检票关键节点；
 * 后续退票/作废等节点需要通知场务时在此扩展并补模板即可，发送链路无需改动。
 */
public enum NotifyEventCode {

    /** 检票关键节点：SOLD -> CHECKED */
    TICKET_CHECK;

    public String code() {
        return name();
    }
}
