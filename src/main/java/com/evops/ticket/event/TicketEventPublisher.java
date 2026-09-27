package com.evops.ticket.event;

import com.evops.common.web.RequestContext;
import com.evops.ticket.enums.FlowType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 票生命周期事件发布器：发布时快照当前操作人/请求号。
 */
@Component
public class TicketEventPublisher {

    private final ApplicationEventPublisher publisher;

    public TicketEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(Long ticketId, FlowType flowType, String reason) {
        publisher.publishEvent(new TicketLifecycleEvent(ticketId, flowType, reason,
                RequestContext.operator(), RequestContext.requestId()));
    }
}
