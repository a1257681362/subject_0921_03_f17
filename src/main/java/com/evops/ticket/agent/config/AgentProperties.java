package com.evops.ticket.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 代理售票平台对接参数（前缀 evops.agent）。
 */
@Component
@ConfigurationProperties(prefix = "evops.agent")
public class AgentProperties {

    /** 总开关：false 时业务不实际推送，仅落 PENDING 归档行（平台排期未就绪时先留档） */
    private boolean enabled = true;

    /** 平台收票地址（出站投递目标） */
    private String endpoint = "http://localhost:8080/agent-stub/ticket";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** 读取超时（毫秒） */
    private int readTimeoutMs = 5000;

    /** 单条报文最大重试次数（不含首发） */
    private int maxRetry = 5;

    /** 固定退避基数（秒），第 n 次失败后延迟 base*2^(n-1)，上限 {@link #maxBackoffSeconds} */
    private int baseBackoffSeconds = 30;

    /** 退避上限（秒），默认 30 分钟 */
    private int maxBackoffSeconds = 1800;

    /** 定时重试每批最多处理条数 */
    private int retryBatchSize = 100;

    /** 场馆在平台侧的身份标识 */
    private String venueCode = "VENUE";

    @PostConstruct
    public void validate() {
        if (maxRetry < 0 || baseBackoffSeconds <= 0 || maxBackoffSeconds < baseBackoffSeconds
                || connectTimeoutMs <= 0 || readTimeoutMs <= 0 || retryBatchSize <= 0) {
            throw new IllegalStateException("evops.agent 重试/超时参数配置非法");
        }
    }

    /** 第 attempt 次失败后的退避秒数（attempt 从 1 起）。 */
    public long backoffSeconds(int attempt) {
        long seconds = (long) baseBackoffSeconds * (1L << Math.min(attempt - 1, 12));
        return Math.min(seconds, maxBackoffSeconds);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
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
    public String getVenueCode() { return venueCode; }
    public void setVenueCode(String venueCode) { this.venueCode = venueCode; }
}
