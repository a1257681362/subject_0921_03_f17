package com.evops.auth.web;

import com.evops.auth.security.AuthContext;
import com.evops.auth.security.JwtClaims;
import com.evops.auth.service.AuthService;
import com.evops.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 访问令牌拦截器：Authorization: Bearer &lt;JWT&gt;。
 * 校验失败一律 401 + ApiResponse JSON；校验通过把登录态放进 {@link AuthContext}。
 */
public class JwtAuthInterceptor implements HandlerInterceptor {

    public static final String BEARER_PREFIX = "Bearer ";

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public JwtAuthInterceptor(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return reject(response, "缺少访问令牌，请先登录");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            return reject(response, "访问令牌格式非法");
        }

        JwtClaims claims;
        try {
            claims = authService.authenticate(token);
        } catch (AuthException e) {
            return reject(response, e.getMessage());
        } catch (IllegalArgumentException e) {
            // 签名/格式/过期问题（JwtService 抛 IllegalArgumentException）必须同样 401，
            // 不能漏给全局兜底处理器变成 200/success=false
            return reject(response, "登录态无效，请重新登录");
        }
        AuthContext.set(new AuthContext.CurrentUser(
                claims.getUserId(), claims.getUsername(), claims.getRole(),
                claims.getRoles(), claims.getPermissions(),
                claims.getJti(), claims.getExp()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    private boolean reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(message)));
        return false;
    }
}
