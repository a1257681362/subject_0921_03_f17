package com.evops.rbac.dto;

import lombok.Data;

import java.util.List;

/**
 * 当前登录人的菜单装配结果：菜单树（visible 节点）+ 扁平权限码集（供按钮级显隐）。
 */
@Data
public class MenuSnapshotVO {
    private List<MenuNodeVO> menus;
    private List<String> permissions;
}
