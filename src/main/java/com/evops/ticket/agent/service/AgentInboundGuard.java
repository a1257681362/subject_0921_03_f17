package com.evops.ticket.agent.service;

/**
 * 入站来源守卫：标记当前线程正在处理平台 -> 场馆的票状态同步。
 *
 * <p>用途：入站报文调用票状态机后会触发 AFTER_COMMIT 生命周期事件，
 * 若照常出站会把平台自己发起的变更再回推给平台（回声/环路）。
 * 守卫在同线程同步提交阶段仍然有效，监听器据此跳过本次出站。
 * 事务监听器默认在提交线程同步执行，故 ThreadLocal 可见。
 */
public final class AgentInboundGuard {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private AgentInboundGuard() {
    }

    public static void enter() {
        ACTIVE.set(true);
    }

    public static void exit() {
        ACTIVE.remove();
    }

    public static boolean isInbound() {
        return ACTIVE.get();
    }
}
