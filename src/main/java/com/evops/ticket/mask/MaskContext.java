package com.evops.ticket.mask;

import com.evops.ticket.mask.entity.SensitiveViewLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 请求级脱敏上下文（ThreadLocal）：记录当前查看人（用户 id/工号/岗位集合）、出口通道
 * （API/EXPORT）、请求路径与请求号，并收集本次响应序列化期间产生的明文查看留痕。
 *
 * <p>一人多岗：{@link #roleCodes} 是当前启用岗位并集，白名单按交集判定（任一岗在白名单
 * 即放行）；{@link #roleCode} 为主岗位，仅兼容保留。留痕 roleCode 记录「实际命中白名单
 * 的那个岗位」（由 MaskService 逐次写入），以表达凭哪一岗看到明文。
 *
 * <p>由 {@code MaskLogInterceptor} 在认证之后建立、请求结束时刷库并清理；
 * 无上下文（定时任务/异步线程里的序列化）按安全失败关闭处理——一律打码、不留痕。
 */
public final class MaskContext {

    private static final ThreadLocal<MaskContext> HOLDER = new ThreadLocal<>();

    private final long userId;
    private final String username;
    private final String roleCode;
    private final Set<String> roleCodes;
    private final String requestPath;
    private final String requestId;
    private String channel;
    private final List<SensitiveViewLog> viewLogs = new ArrayList<>();
    /** 同一请求内（单据, 字段, 出口）去重：序列化链路可能二次经过同一值（Object→Map 包装），
     *  一次查看动作只留一行；不同单据/不同字段各自成行。 */
    private final java.util.Set<String> dedupKeys = new java.util.HashSet();

    private MaskContext(long userId, String username, String roleCode, Set<String> roleCodes,
                        String channel, String requestPath, String requestId) {
        this.userId = userId;
        this.username = username;
        this.roleCode = roleCode;
        this.roleCodes = roleCodes == null ? Collections.emptySet() : Collections.unmodifiableSet(roleCodes);
        this.channel = channel;
        this.requestPath = requestPath;
        this.requestId = requestId;
    }

    public static void begin(long userId, String username, String roleCode, Set<String> roleCodes,
                             String channel, String requestPath, String requestId) {
        HOLDER.set(new MaskContext(userId, username, roleCode, roleCodes,
                channel, requestPath, requestId));
    }

    public static MaskContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 导出接口在同请求线程内切换出口通道（API → EXPORT），用完复位；切换后按新通道另行留痕。 */
    public void overrideChannel(String channel) {
        this.channel = channel;
    }

    /** 同请求同单据同字段同通道的明文查看只记一次（防序列化二次经过造成重复留痕）。 */
    public boolean addViewLogIfFirst(SensitiveViewLog log) {
        String key = log.getBizType() + "|" + log.getBizId() + "|"
                + log.getFieldName() + "|" + channel;
        if (dedupKeys.add(key)) {
            viewLogs.add(log);
            return true;
        }
        return false;
    }

    /** 测试/兼容：追加留痕（不去重）。 */
    public void addViewLog(SensitiveViewLog log) {
        addViewLogIfFirst(log);
    }

    public List<SensitiveViewLog> drainViewLogs() {
        if (viewLogs.isEmpty()) {
            return Collections.emptyList();
        }
        List<SensitiveViewLog> copy = new ArrayList<>(viewLogs);
        viewLogs.clear();
        return copy;
    }

    public long getUserId() { return userId; }
    public String getUsername() { return username; }
    /** 主岗位（兼容）。留痕的实际命中角色见各条 viewLog.roleCode。 */
    public String getRoleCode() { return roleCode; }
    /** 当前启用岗位 code 并集（多岗白名单交集判定用）。 */
    public Set<String> getRoleCodes() { return roleCodes; }
    public String getChannel() { return channel; }
    public String getRequestPath() { return requestPath; }
    public String getRequestId() { return requestId; }
}
