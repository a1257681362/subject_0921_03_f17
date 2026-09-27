package com.evops.common.web;

/**
 * 写请求上下文：操作人、请求号由请求头注入，贯穿同一次 HTTP 调用。
 */
public final class RequestContext {
    private static final ThreadLocal<Context> HOLDER = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(Context context) {
        HOLDER.set(context);
    }

    public static Context get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static String operator() {
        Context ctx = HOLDER.get();
        return ctx == null ? "system" : ctx.getOperator();
    }

    /** BaseEntity.createBy/updateBy 为数字用户，非数字操作人降级为 0(system)。 */
    public static Long auditUserId() {
        Context ctx = HOLDER.get();
        return ctx == null ? 0L : ctx.getAuditUserId();
    }

    public static String requestId() {
        Context ctx = HOLDER.get();
        return ctx == null ? null : ctx.getRequestId();
    }

    public static class Context {
        private final String operator;
        private final String requestId;
        private final String path;
        private final Long auditUserId;

        public Context(String operator, String requestId, String path, Long auditUserId) {
            this.operator = operator;
            this.requestId = requestId;
            this.path = path;
            this.auditUserId = auditUserId;
        }

        public String getOperator() {
            return operator;
        }

        public String getRequestId() {
            return requestId;
        }

        public String getPath() {
            return path;
        }

        public Long getAuditUserId() {
            return auditUserId;
        }
    }
}
