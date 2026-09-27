package com.evops.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RBAC 角色（岗位）。一人可绑多个角色，授权时多角色取【并集】。
 * status=DISABLED 的角色对所有绑定者即时收权（授权每请求实时装载）。
 * builtin=1 为内置角色，禁止删除、code 禁止修改。
 */
@Data
@TableName("t_rbac_role")
public class RbacRole {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 角色码，全局唯一，如 BOX_OFFICE；接口权限与脱敏白名单均按 code 判定 */
    private String code;
    private String name;
    /** ACTIVE / DISABLED */
    private String status;
    /** 1 内置角色 */
    private Integer builtin;
    private String remark;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
