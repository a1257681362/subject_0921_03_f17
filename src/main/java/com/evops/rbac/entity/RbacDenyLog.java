package com.evops.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 越权访问审计记录（只追加不修改）。
 * 每次已认证用户命中权限不足，由 RbacInterceptor 统一落一行：
 * 谁（user/工号）、当时启用角色并集、请求方法与路径、所需权限码、来源 IP、请求号、时间。
 */
@Data
@TableName("t_rbac_deny_log")
public class RbacDenyLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    /** 当时实际启用角色 code 并集（CSV，按字典序） */
    private String rolesCsv;
    private String requestMethod;
    private String requestPath;
    /** 注解要求的权限码（可能多个，CSV） */
    private String requiredPermission;
    private String clientIp;
    private String requestId;
    private LocalDateTime denyTime;
}
