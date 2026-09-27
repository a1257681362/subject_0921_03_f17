package com.evops.ticket.dto.common;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 写请求公共字段：业务时间。缺省时服务层取服务器时间。
 */
@Data
public class BizTimeRequest {
    private LocalDateTime bizTime;
}
