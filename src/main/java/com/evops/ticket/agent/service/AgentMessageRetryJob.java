package com.evops.ticket.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 出站失败报文退避重试：每分钟扫描到期的 PENDING/RETRYING 行重投。
 * 单条异常不影响整批；重试次数与退避在 {@link OutboundAgentService} 内裁定，耗尽置 FAILED。
 */
@Component
public class AgentMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageRetryJob.class);

    private final OutboundAgentService outboundService;

    public AgentMessageRetryJob(OutboundAgentService outboundService) {
        this.outboundService = outboundService;
    }

    @Scheduled(cron = "0 * * * * ?")
    public void retry() {
        try {
            int count = outboundService.retryDue();
            if (count > 0) {
                log.info("代理平台出站重试本轮处理 {} 条", count);
            }
        } catch (Exception e) {
            log.error("代理平台出站重试任务执行失败", e);
        }
    }
}
