package com.evops.rbac.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 角色-菜单授权（覆盖式）：提交后角色拥有的菜单/权限点 = menuIds 全集。
 */
@Data
public class RoleMenuGrantRequest {
    private List<Long> menuIds = new ArrayList<>();
}
