package com.evops.rbac.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 新建角色请求。code 一经创建不可修改（内置/自定义均如此），4-32 位大写字母数字下划线。
 */
@Data
public class RoleCreateRequest {
    @NotBlank(message = "角色码不能为空")
    @Pattern(regexp = "[A-Z][A-Z0-9_]{3,31}", message = "角色码需为 4-32 位大写字母/数字/下划线，且以字母开头")
    private String code;

    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称最长 64 字")
    private String name;

    @Size(max = 255, message = "备注最长 255 字")
    private String remark;
}
