package com.evops.ticket.mask.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感字段明文查看留痕视图（只追加，管理端只读）。
 */
@Data
public class SensitiveViewLogVO {
    private Long id;
    private Long userId;
    private String username;
    private String roleCode;
    private String bizType;
    private String bizId;
    private String fieldName;
    private String maskType;
    private Integer ruleVersion;
    /** API 接口返回 / EXPORT 导出 */
    private String channel;
    private String requestPath;
    private String requestId;
    private LocalDateTime viewTime;
}
