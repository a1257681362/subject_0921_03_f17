package com.evops.ticket.notify.channel;

/**
 * 通道发送结果。transportError=true 表示未拿到对端应答（连接/读超时等），
 * 与非 2xx 一样进入重试；messageId 为对端回执号（无则空）。
 */
public class ChannelSendResult {

    private final boolean success;
    private final Integer httpStatus;
    private final String messageId;
    private final String error;

    private ChannelSendResult(boolean success, Integer httpStatus, String messageId, String error) {
        this.success = success;
        this.httpStatus = httpStatus;
        this.messageId = messageId;
        this.error = error;
    }

    public static ChannelSendResult ok(Integer httpStatus, String messageId) {
        return new ChannelSendResult(true, httpStatus, messageId, null);
    }

    public static ChannelSendResult fail(Integer httpStatus, String error) {
        return new ChannelSendResult(false, httpStatus, null, error);
    }

    public boolean isSuccess() { return success; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getMessageId() { return messageId; }
    public String getError() { return error; }
}
