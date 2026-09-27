package com.evops.rbac.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 角色视图（管理端）。userCount 为当前绑定该角色的用户数（含停用用户）。
 */
@Data
public class RoleVO {
    private Long id;
    private String code;
    private String name;
    private String status;
    private Integer builtin;
    private String remark;
    private Long userCount;
    private LocalDateTime createTime;
}
