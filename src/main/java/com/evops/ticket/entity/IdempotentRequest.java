package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 写请求幂等记录：同 requestId 重复请求直接回放首次响应快照。
 */
@Data
@TableName("t_idempotent_request")
public class IdempotentRequest {
    @TableId(type = IdType.INPUT)
    private String requestId;
    private String requestPath;
    private String requestHash;
    /** PROCESSING / DONE */
    private String status;
    private String businessKey;
    private String responseSnapshot;
    private String operator;
    private LocalDateTime createTime;
    private LocalDateTime finishTime;
}
