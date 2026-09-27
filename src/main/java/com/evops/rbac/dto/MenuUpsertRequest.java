package com.evops.rbac.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.PositiveOrZero;
import javax.validation.constraints.Size;

/**
 * 菜单节点新增请求。parentId=0 为根；type=DIR/MENU/BUTTON；permission 非空须全局唯一。
 */
@Data
public class MenuUpsertRequest {
    @NotNull(message = "父节点不能为空（根节点传 0）")
    @PositiveOrZero(message = "父节点 id 非法")
    private Long parentId;

    @NotBlank(message = "节点类型不能为空")
    @Pattern(regexp = "DIR|MENU|BUTTON", message = "节点类型仅支持 DIR/MENU/BUTTON")
    private String type;

    @NotBlank(message = "节点名称不能为空")
    @Size(max = 64, message = "节点名称最长 64 字")
    private String name;

    /** 权限码；目录可空，其余建议必填；格式 段:段:段（字母数字下划线中划线） */
    @Pattern(regexp = "|[a-zA-Z][a-zA-Z0-9_:-]{2,95}", message = "权限码格式非法（如 ticket:order:create）")
    private String permission;

    @Size(max = 128, message = "路由最长 128 字")
    private String path;

    @Size(max = 64, message = "图标最长 64 字")
    private String icon;

    private Integer sortNo = 0;
    private Integer visible = 1;
    /** 更新时可改启停；新增默认 ENABLED */
    private String status;
}
