package com.evops.auth.controller;

import com.evops.auth.dto.AdminProfileVO;
import com.evops.auth.dto.ChangePasswordRequest;
import com.evops.auth.dto.LoginRequest;
import com.evops.auth.dto.LogoutRequest;
import com.evops.auth.dto.RefreshRequest;
import com.evops.auth.dto.TokenPairVO;
import com.evops.auth.security.AuthContext;
import com.evops.auth.security.JwtClaims;
import com.evops.auth.service.AuthService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.Collections;
import java.util.Map;

/**
 * 场馆后台认证端点（整改 auth-1）。
 * login / refresh 免登录；logout / change-password / me 需访问令牌。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<TokenPairVO> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request.getUsername().trim(), request.getPassword()));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenPairVO> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.getRefreshToken().trim()));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Object>> logout(@RequestBody(required = false) LogoutRequest request) {
        AuthContext.CurrentUser current = AuthContext.get();
        JwtClaims access = new JwtClaims();
        access.setUserId(current.getUserId());
        access.setUsername(current.getUsername());
        access.setJti(current.getJti());
        access.setExp(current.getAccessExpEpochSecond());
        authService.logout(access, request == null ? null : request.getRefreshToken());
        return ApiResponse.ok(Collections.singletonMap("loggedOut", true));
    }

    @PostMapping("/change-password")
    public ApiResponse<Map<String, Object>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        AuthContext.CurrentUser current = AuthContext.get();
        JwtClaims access = new JwtClaims();
        access.setUserId(current.getUserId());
        access.setUsername(current.getUsername());
        access.setJti(current.getJti());
        access.setExp(current.getAccessExpEpochSecond());
        authService.changePassword(
                access, request.getOldPassword(), request.getNewPassword());
        // 全端下线：不签发新令牌，前端必须用新密码重新登录
        return ApiResponse.ok(Collections.singletonMap("reloginRequired", true));
    }

    @GetMapping("/me")
    public ApiResponse<AdminProfileVO> me() {
        return ApiResponse.ok(authService.profile(AuthContext.get().getUserId()));
    }
}
