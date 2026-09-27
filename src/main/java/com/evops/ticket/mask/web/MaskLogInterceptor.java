package com.evops.ticket.mask.web;

import com.evops.auth.security.AuthContext;
import com.evops.common.web.RequestContext;
import com.evops.ticket.mask.entity.SensitiveViewLog;
import com.evops.ticket.mask.MaskContext;
import com.evops.ticket.mask.mapper.SensitiveViewLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * 脱敏上下文拦截器：在认证与请求上下文之后建立 {@link MaskContext}（谁、什么角色、
 * 走哪个出口），响应序列化完成后把本次产生的明文查看留痕统一刷库并清理。
 *
 * <p>无登录态（匿名端点/未到达认证）不建上下文：序列化侧按安全失败关闭一律打码。
 * 留痕写库失败不得影响已生成的响应，但打 ERROR 保证可发现、可追查。
 */
public class MaskLogInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MaskLogInterceptor.class);

    private final SensitiveViewLogMapper viewLogMapper;

    public MaskLogInterceptor(SensitiveViewLogMapper viewLogMapper) {
        this.viewLogMapper = viewLogMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        AuthContext.CurrentUser user = AuthContext.get();
        if (user != null) {
            RequestContext.Context ctx = RequestContext.get();
            MaskContext.begin(user.getUserId(), user.getUsername(), user.getRole(),
                    user.getRoles(),
                    SensitiveViewLog.CHANNEL_API,
                    request.getRequestURI(),
                    ctx == null ? null : ctx.getRequestId());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        MaskContext ctx = MaskContext.get();
        if (ctx != null) {
            try {
                List<SensitiveViewLog> logs = ctx.drainViewLogs();
                for (SensitiveViewLog row : logs) {
                    try {
                        viewLogMapper.insert(row);
                    } catch (Exception e) {
                        // 留痕失败必须可见：打 ERROR 且带定位信息，绝不静默
                        log.error("敏感字段明文查看留痕写库失败 user={} role={} path={} biz={}/{} field={}",
                                row.getUsername(), row.getRoleCode(), row.getRequestPath(),
                                row.getBizType(), row.getBizId(), row.getFieldName(), e);
                    }
                }
            } finally {
                MaskContext.clear();
            }
        }
    }
}
