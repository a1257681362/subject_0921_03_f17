package com.evops.ticket.agent.transport;

/** 平台一次 HTTP 投递的原始结果。 */
public class SendResult {
    private final int httpStatus;
    private final String body;

    public SendResult(int httpStatus, String body) {
        this.httpStatus = httpStatus;
        this.body = body;
    }

    public int getHttpStatus() { return httpStatus; }
    public String getBody() { return body; }
    public boolean is2xxSuccessful() { return httpStatus >= 200 && httpStatus < 300; }
}
