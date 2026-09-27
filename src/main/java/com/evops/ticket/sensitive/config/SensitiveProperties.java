package com.evops.ticket.sensitive.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检票备注敏感词扫描参数（前缀 evops.sensitive）。
 *
 * <p>回退预案（先备好，一出异常立即切回旧逻辑）：
 * <ul>
 *   <li>{@code enabled=false}（或环境变量 {@code EVOPS_SENSITIVE_ENABLED=false}）：
 *       总开关，关闭即完全退回不扫描的旧检票逻辑，无需改代码、无需重启以外的动作；</li>
 *   <li>{@code fail-open=true}：扫描/留痕链路自身抛异常时，本次请求记 ERROR 后按原文放行，
 *       绝不让扫描故障挡住现场检票；置 false 可改为故障即失败（强卡模式，演练用）；</li>
 *   <li>{@code scan-agent-inbound=false}：平台 -> 场馆入站报文默认豁免
 *       （非现场用户手工输入，且误拦会造成平台同步失败），仅扫人工入口文本。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "evops.sensitive")
public class SensitiveProperties {

    /** 总开关：false 完全退回旧逻辑。 */
    private boolean enabled = true;

    /** 扫描链路异常时是否放行（true=故障切旧逻辑；false=故障也拦截，仅演练用）。 */
    private boolean failOpen = true;

    /** 是否扫描代理平台入站报文携带的文本（默认豁免，只扫人工入口）。 */
    private boolean scanAgentInbound = false;

    /** 低危命中的打码字符（逐码点替换命中片段）。 */
    private String maskChar = "*";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public boolean isScanAgentInbound() {
        return scanAgentInbound;
    }

    public void setScanAgentInbound(boolean scanAgentInbound) {
        this.scanAgentInbound = scanAgentInbound;
    }

    public String getMaskChar() {
        return maskChar;
    }

    public void setMaskChar(String maskChar) {
        this.maskChar = maskChar;
    }
}
