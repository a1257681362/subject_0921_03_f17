package com.evops.ticket.agent.transport;

/** 网络层投递异常（连接失败、读超时等），区别于平台返回了 HTTP 响应但非 2xx。 */
public class TransportException extends RuntimeException {
    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
