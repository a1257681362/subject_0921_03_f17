package com.evops.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 菜单/权限点节点，自关联成树（parent_id=0 为根）。
 * type：DIR 目录 / MENU 菜单页面 / BUTTON 按钮或纯权限点。
 * permission 非空时同时是一个权限码（全局唯一）；visible=0 只下发权限集、不出现在菜单树。
 * status=DISABLED 的节点对所有角色即时收权。
 */
@Data
@TableName("t_rbac_menu")
public class RbacMenu {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    /** DIR / MENU / BUTTON */
    private String type;
    private String name;
    /** 权限码，如 ticket:order:create；DIR 可空 */
    private String permission;
    /** 前端路由 */
    private String path;
    private String icon;
    private Integer sortNo;
    /** 1 出现在菜单树 / 0 仅权限点 */
    private Integer visible;
    /** ENABLED / DISABLED */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
