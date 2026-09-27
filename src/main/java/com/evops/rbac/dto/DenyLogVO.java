package com.evops.rbac.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 越权访问审计视图（只追加，管理端只读）。
 */
@Data
public class DenyLogVO {
    private Long id;
    private Long userId;
    private String username;
    private String rolesCsv;
    private String requestMethod;
    private String requestPath;
    private String requiredPermission;
    private String clientIp;
    private String requestId;
    private LocalDateTime denyTime;
}
