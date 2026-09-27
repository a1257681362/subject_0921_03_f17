package com.evops.rbac.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户岗位分配请求（覆盖式）：提交后用户岗位集合 = roleIds 全集；
 * 空数组 = 一键下岗（下一请求所有岗位权限即时收回）。
 */
@Data
public class UserRolesUpdateRequest {
    private List<Long> roleIds = new ArrayList<>();
}
