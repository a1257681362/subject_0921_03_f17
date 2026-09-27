package com.evops.ticket.notify.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 检票通知退避重试：每分钟扫描到期 RETRYING 发送记录续投（最多重试 2 次，耗尽置 FAILED）。
 * 单条异常不影响整批；重试次数与退避在 {@link NotifySendService} 内裁定。
 */
@Component
public class NotifyRetryJob {

    private static final Logger log = LoggerFactory.getLogger(NotifyRetryJob.class);

    private final NotifySendService notifySendService;

    public NotifyRetryJob(NotifySendService notifySendService) {
        this.notifySendService = notifySendService;
    }

    @Scheduled(cron = "0 * * * * ?")
    public void retry() {
        try {
            int count = notifySendService.retryDue();
            if (count > 0) {
                log.info("检票通知失败重试本轮处理 {} 条", count);
            }
        } catch (Exception e) {
            log.error("检票通知重试任务执行失败", e);
        }
    }
}
