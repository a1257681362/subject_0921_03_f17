package com.evops.ticket.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 检票通知参数（前缀 evops.notify）。
 * 失败重试口径与验收要求一致：首发之后再重试 2 次（共投递 3 次），耗尽置 FAILED 可人工重发。
 */
@Component
@ConfigurationProperties(prefix = "evops.notify")
public class NotifyProperties {

    /** 通知总开关：false 时检票事件不触发任何通道（仅业务主流程，不产生通知） */
    private boolean enabled = true;

    /** 站内信通道开关（场务登录后台查收） */
    private boolean inboxEnabled = true;

    /** 邮件通道开关 */
    private boolean emailEnabled = true;

    /** 邮件网关地址（HTTP 投递，演示/联调可指向桩；真实环境由运维配 SMTP 出口网关） */
    private String emailEndpoint = "http://localhost:8080/mail-stub/send";

    /** 邮件发件人显示地址 */
    private String mailFrom = "evops-venue@example.cn";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** 读取超时（毫秒） */
    private int readTimeoutMs = 5000;

    /** 失败重试次数（不含首发）：验收口径固定 2 次 */
    private int maxRetry = 2;

    /** 退避基数（秒），第 n 次失败后延迟 base*2^(n-1)，上限 {@link #maxBackoffSeconds} */
    private int baseBackoffSeconds = 10;

    /** 退避上限（秒），默认 5 分钟 */
    private int maxBackoffSeconds = 300;

    /** 定时重试每批最多处理条数 */
    private int retryBatchSize = 100;

    /**
     * 邮件外呼异步化：检票事务提交后，站内信仍在当前线程即时投递（本地库、毫秒级），
     * 邮件 HTTP 外呼交给独立线程池，检票接口不再被邮件网关超时拖住。
     * 预案开关：置 false 立即回到"邮件同步外呼"的旧行为（无需改代码）。
     */
    private boolean emailAsync = true;

    /** 邮件外呼线程池大小：外呼为 IO 等待型，2 个线程足以并发多张票的通知 */
    private int emailPoolSize = 2;

    /**
     * 首投兜底时限（秒）：邮件记录已落 PENDING 但超过该时限仍未投递（异步线程当掉/
     * 线程池满走拒绝、进程在提交后崩溃），由每分钟定时任务补做首次投递。
     * 必须显著大于单次外呼超时上限（connect+read），避免与正在进行的首投竞争。
     */
    private int pendingFirstDeliverySeconds = 90;

    @PostConstruct
    public void validate() {
        if (maxRetry < 0 || baseBackoffSeconds <= 0 || maxBackoffSeconds < baseBackoffSeconds
                || connectTimeoutMs <= 0 || readTimeoutMs <= 0 || retryBatchSize <= 0
                || emailPoolSize <= 0 || pendingFirstDeliverySeconds <= 0) {
            throw new IllegalStateException("evops.notify 重试/超时参数配置非法");
        }
        long singleCallBudgetMs = (long) connectTimeoutMs + readTimeoutMs;
        if (emailAsync && pendingFirstDeliverySeconds * 1000L <= singleCallBudgetMs) {
            throw new IllegalStateException("evops.notify.pending-first-delivery-seconds 必须大于"
                    + "单次邮件外呼超时上限（connect+read=" + singleCallBudgetMs + "ms）");
        }
    }

    /** 第 attempt 次失败后的退避秒数（attempt 从 1 起）。 */
    public long backoffSeconds(int attempt) {
        long seconds = (long) baseBackoffSeconds * (1L << Math.min(attempt - 1, 12));
        return Math.min(seconds, maxBackoffSeconds);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isInboxEnabled() { return inboxEnabled; }
    public void setInboxEnabled(boolean v) { this.inboxEnabled = v; }
    public boolean isEmailEnabled() { return emailEnabled; }
    public void setEmailEnabled(boolean v) { this.emailEnabled = v; }
    public String getEmailEndpoint() { return emailEndpoint; }
    public void setEmailEndpoint(String v) { this.emailEndpoint = v; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String v) { this.mailFrom = v; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
    public int getMaxRetry() { return maxRetry; }
    public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
    public int getBaseBackoffSeconds() { return baseBackoffSeconds; }
    public void setBaseBackoffSeconds(int v) { this.baseBackoffSeconds = v; }
    public int getMaxBackoffSeconds() { return maxBackoffSeconds; }
    public void setMaxBackoffSeconds(int v) { this.maxBackoffSeconds = v; }
    public int getRetryBatchSize() { return retryBatchSize; }
    public void setRetryBatchSize(int v) { this.retryBatchSize = v; }
    public boolean isEmailAsync() { return emailAsync; }
    public void setEmailAsync(boolean v) { this.emailAsync = v; }
    public int getEmailPoolSize() { return emailPoolSize; }
    public void setEmailPoolSize(int v) { this.emailPoolSize = v; }
    public int getPendingFirstDeliverySeconds() { return pendingFirstDeliverySeconds; }
    public void setPendingFirstDeliverySeconds(int v) { this.pendingFirstDeliverySeconds = v; }
}
