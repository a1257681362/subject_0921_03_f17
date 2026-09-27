package com.evops.auth.dto;

import lombok.Data;

import java.util.List;

@Data
public class AdminProfileVO {
    private Long id;
    private String username;
    private String displayName;
    private String venue;
    /** 主岗位（兼容单角色口径）。 */
    private String role;
    /** 当前启用岗位 code 集合（一人多岗，并集鉴权/动态菜单的依据）。 */
    private List<String> roles;
}
