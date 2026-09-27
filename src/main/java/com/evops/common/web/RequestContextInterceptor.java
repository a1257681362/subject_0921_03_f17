package com.evops.common.web;

import com.evops.auth.security.AuthContext;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * 从请求头读取操作人与请求号：
 * X-Operator-Id 缺省取 JWT 登录工号，再缺省为 system；
 * X-Request-Id 缺省生成 UUID（不具备双击防护，仅保证不阻断请求）。
 */
public class RequestContextInterceptor implements HandlerInterceptor {

    public static final String HEADER_OPERATOR = "X-Operator-Id";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String operator = request.getHeader(HEADER_OPERATOR);
        AuthContext.CurrentUser loginUser = AuthContext.get();
        Long auditUserId = 0L;
        if (!StringUtils.hasText(operator)) {
            // 已登录后台请求：操作人绑定登录工号，审计用户 id 取账号主键
            if (loginUser != null) {
                operator = loginUser.getUsername();
                auditUserId = loginUser.getUserId();
            } else {
                operator = "system";
            }
        } else if (StringUtils.hasText(operator) && operator.matches("\\d+")) {
            auditUserId = Long.parseLong(operator);
        }
        String requestId = request.getHeader(HEADER_REQUEST_ID);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        RequestContext.set(new RequestContext.Context(operator, requestId, request.getRequestURI(), auditUserId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContext.clear();
    }
}
