package com.evops.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户-岗位关联（一人多岗）。同一用户可绑多行；授权只计角色 status=ACTIVE 的行。
 */
@Data
@TableName("t_admin_user_role")
public class AdminUserRole {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long roleId;
}
