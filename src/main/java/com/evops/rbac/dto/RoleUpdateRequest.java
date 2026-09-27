package com.evops.rbac.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 角色信息更新请求（code 不可改；状态走专用启停接口）。
 */
@Data
public class RoleUpdateRequest {
    @NotBlank(message = "角色名称不能为空")
    @Size(max = 64, message = "角色名称最长 64 字")
    private String name;

    @Size(max = 255, message = "备注最长 255 字")
    private String remark;
}
