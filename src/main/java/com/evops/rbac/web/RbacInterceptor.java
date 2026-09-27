package com.evops.rbac.web;

import com.evops.auth.security.AuthContext;
import com.evops.common.ApiResponse;
import com.evops.common.web.RequestContext;
import com.evops.rbac.annotation.Logical;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.rbac.entity.RbacDenyLog;
import com.evops.rbac.mapper.RbacDenyLogMapper;
import com.evops.rbac.service.RbacQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 统一权限拦截：在认证（order=0）之后解析 {@link RequirePermission}，
 * 用当前登录人每请求实时装载的权限码【并集】判定——命中任一即放行；
 * ADMIN 代码层短路。不满足统一回 403（ApiResponse JSON）并落 t_rbac_deny_log。
 *
 * <p>停用角色即时收权由此自然成立：权限集来自本次请求的库内最新状态，
 * 被拦截人的旧 JWT 无需失效也会在下一请求被挡。
 *
 * <p>越权留痕写库失败只打 ERROR，绝不影响 403 响应，更不能因审计故障放行。
 */
public class RbacInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RbacInterceptor.class);

    private final RbacDenyLogMapper denyLogMapper;
    private final ObjectMapper objectMapper;

    public RbacInterceptor(RbacDenyLogMapper denyLogMapper, ObjectMapper objectMapper) {
        this.denyLogMapper = denyLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        AuthContext.CurrentUser user = AuthContext.get();
        if (user == null) {
            // 拦截路径与 JWT 拦截器一致，正常不会到这里；防御性按无权限处理
            return reject(request, response, null, new String[]{"<authenticated>"});
        }

        RequirePermission required = resolve((HandlerMethod) handler);
        if (required == null) {
            return true; // 仅认证端点
        }
        if (user.hasRole(RbacQueryService.ROLE_ADMIN) || granted(user, required)) {
            return true;
        }
        return reject(request, response, user, required.value());
    }

    /** 方法级声明存在即整体覆盖类级（不拼接，防止权限意外放大）。 */
    private RequirePermission resolve(HandlerMethod handlerMethod) {
        RequirePermission onMethod = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (onMethod != null) {
            return onMethod;
        }
        return handlerMethod.getBeanType().getAnnotation(RequirePermission.class);
    }

    private boolean granted(AuthContext.CurrentUser user, RequirePermission required) {
        if (required.logical() == Logical.ALL) {
            return user.getPermissions().containsAll(Arrays.asList(required.value()));
        }
        return user.hasAnyPermission(required.value());
    }

    private boolean reject(HttpServletRequest request, HttpServletResponse response,
                          AuthContext.CurrentUser user, String[] requiredPerms) throws Exception {
        recordDeny(request, user, requiredPerms);
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.fail("无权限访问该功能（需要：" + String.join(",", requiredPerms) + "）")));
        return false;
    }

    private void recordDeny(HttpServletRequest request, AuthContext.CurrentUser user, String[] requiredPerms) {
        try {
            RbacDenyLog row = new RbacDenyLog();
            if (user != null) {
                row.setUserId(user.getUserId());
                row.setUsername(user.getUsername());
                List<String> roles = new ArrayList<>(user.getRoles());
                java.util.Collections.sort(roles);
                row.setRolesCsv(String.join(",", roles));
            } else {
                row.setUserId(0L);
                row.setUsername("<anonymous>");
                row.setRolesCsv("");
            }
            row.setRequestMethod(request.getMethod());
            row.setRequestPath(request.getRequestURI());
            row.setRequiredPermission(String.join(",", requiredPerms));
            row.setClientIp(clientIp(request));
            row.setRequestId(RequestContext.requestId());
            row.setDenyTime(LocalDateTime.now());
            denyLogMapper.insert(row);
        } catch (Exception e) {
            // 审计故障只记录、不抛出：403 判定与响应不能受影响
            log.error("越权访问留痕写库失败 user={} {} {} required={}",
                    user == null ? "<anonymous>" : user.getUsername(),
                    request.getMethod(), request.getRequestURI(),
                    Arrays.toString(requiredPerms), e);
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
