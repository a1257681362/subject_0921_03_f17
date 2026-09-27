package com.evops.rbac.web;

import com.evops.auth.security.AuthContext;
import com.evops.common.ApiResponse;
import com.evops.rbac.dto.MenuSnapshotVO;
import com.evops.rbac.service.MenuTreeAssembler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录人菜单装配（一人多岗，按并集动态装配）。
 * 登录后前端调用本端点拉取：visible 菜单树（多岗共有菜单去重）+ 扁平权限码集（按钮显隐）。
 * 每请求实时计算，停用角色/调整授权后再次拉取即变化，无需重新登录。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthMenuController {

    private final MenuTreeAssembler menuTreeAssembler;

    public AuthMenuController(MenuTreeAssembler menuTreeAssembler) {
        this.menuTreeAssembler = menuTreeAssembler;
    }

    @GetMapping("/menus")
    public ApiResponse<MenuSnapshotVO> myMenus() {
        long userId = AuthContext.requireUserId();
        return ApiResponse.ok(menuTreeAssembler.assembleForUser(userId));
    }
}
