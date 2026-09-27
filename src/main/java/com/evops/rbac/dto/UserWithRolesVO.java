package com.evops.rbac.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户及其当前岗位集合（管理端分配页）。
 */
@Data
public class UserWithRolesVO {
    private Long id;
    private String username;
    private String displayName;
    private String venue;
    /** 主岗位（t_admin_user.role 单值，随岗位分配同步为首个启用岗位 code） */
    private String primaryRole;
    private String status;
    private List<RoleVO> roles = new ArrayList<>();
}
