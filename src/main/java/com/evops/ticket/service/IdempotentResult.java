package com.evops.ticket.service;

/**
 * 幂等业务执行结果：businessKey 记录首次业务主键（如 ORDER:12），data 为响应数据。
 */
public class IdempotentResult<T> {
    private final String businessKey;
    private final T data;

    private IdempotentResult(String businessKey, T data) {
        this.businessKey = businessKey;
        this.data = data;
    }

    public static <T> IdempotentResult<T> of(String businessKey, T data) {
        return new IdempotentResult<>(businessKey, data);
    }

    public String getBusinessKey() {
        return businessKey;
    }

    public T getData() {
        return data;
    }
}
