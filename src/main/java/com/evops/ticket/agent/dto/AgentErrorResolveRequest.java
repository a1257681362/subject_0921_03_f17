package com.evops.ticket.agent.dto;

import lombok.Data;

/** 异常清单人工处理请求。 */
@Data
public class AgentErrorResolveRequest {
    /** RESOLVED 已处理 / IGNORED 已忽略 */
    private String status;
    private String remark;
}
