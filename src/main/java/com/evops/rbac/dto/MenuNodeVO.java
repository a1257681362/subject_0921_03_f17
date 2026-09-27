package com.evops.rbac.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点（下发给前端动态装配侧边栏）。
 */
@Data
public class MenuNodeVO {
    private Long id;
    private Long parentId;
    /** DIR / MENU / BUTTON */
    private String type;
    private String name;
    private String permission;
    private String path;
    private String icon;
    private Integer sortNo;
    private Integer visible;
    private List<MenuNodeVO> children = new ArrayList<>();
}
