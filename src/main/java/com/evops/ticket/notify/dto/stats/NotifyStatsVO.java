package com.evops.ticket.notify.dto.stats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发送结果统计：按通道给 总数/成功/失败/重试中/跳过，外加批次汇总与成功率。
 */
public class NotifyStatsVO {

    private String eventCode;
    private Long totalDispatches;
    private Long successDispatches;
    private Long partialDispatches;
    private Long failedDispatches;

    /** channel -> 状态计数 */
    private Map<String, Map<String, Long>> channels = new LinkedHashMap<>();

    public String getEventCode() { return eventCode; }
    public void setEventCode(String eventCode) { this.eventCode = eventCode; }
    public Long getTotalDispatches() { return totalDispatches; }
    public void setTotalDispatches(Long totalDispatches) { this.totalDispatches = totalDispatches; }
    public Long getSuccessDispatches() { return successDispatches; }
    public void setSuccessDispatches(Long successDispatches) { this.successDispatches = successDispatches; }
    public Long getPartialDispatches() { return partialDispatches; }
    public void setPartialDispatches(Long partialDispatches) { this.partialDispatches = partialDispatches; }
    public Long getFailedDispatches() { return failedDispatches; }
    public void setFailedDispatches(Long failedDispatches) { this.failedDispatches = failedDispatches; }
    public Map<String, Map<String, Long>> getChannels() { return channels; }
    public void setChannels(Map<String, Map<String, Long>> channels) { this.channels = channels; }
}
