package com.evops.ticket.notify.service;

import com.evops.ticket.enums.FlowType;
import com.evops.ticket.event.TicketLifecycleEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 票生命周期 -> 检票关键节点通知场务。
 *
 * <p>仅在检票状态变更事务<b>提交后</b>触发（AFTER_COMMIT）：通知的检票结果已落库；
 * 事务回滚不通知。{@code fallbackExecution=true} 使无事务上下文（测试/补通知）也能消费。
 * 通知异常不外溢，主流程（含平台出站）互不影响。
 *
 * <p>当前开演保障只放检票节点；后续要扩退票/作废节点，加事件码、模板与收件人即可。
 */
@Component
public class CheckinNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(CheckinNotifyListener.class);

    private final NotifySendService notifySendService;

    public CheckinNotifyListener(NotifySendService notifySendService) {
        this.notifySendService = notifySendService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTicketLifecycle(TicketLifecycleEvent event) {
        if (event.getFlowType() != FlowType.CHECK) {
            return;
        }
        try {
            notifySendService.onTicketCheck(event.getTicketId(), event.getOperator(),
                    event.getRequestId(), event.getReason());
        } catch (Exception e) {
            log.error("检票事件通知场务失败 ticketId={}", event.getTicketId(), e);
        }
    }
}
