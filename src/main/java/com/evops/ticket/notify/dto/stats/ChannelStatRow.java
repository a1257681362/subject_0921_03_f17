package com.evops.ticket.notify.dto.stats;

/**
 * 发送量按 通道 × 状态 分组的一行统计结果。
 */
public class ChannelStatRow {

    private String channel;
    private String status;
    private Long cnt;

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCnt() { return cnt; }
    public void setCnt(Long cnt) { this.cnt = cnt; }
}
