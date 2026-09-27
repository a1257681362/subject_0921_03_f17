package com.evops.ticket.sensitive;

/**
 * 敏感词扫描场景：标识文本从哪个检票流程入口进来，随命中留痕落库，便于分场景审计。
 */
public final class SensitiveScene {

    /** 入场检票备注 */
    public static final String CHECK = "CHECK";
    /** 退票备注 */
    public static final String REFUND = "REFUND";
    /** 票作废备注 */
    public static final String VOID = "VOID";
    /** 整单作废备注 */
    public static final String VOID_ORDER = "VOID_ORDER";
    /** 取消检票排期备注 */
    public static final String APPT_CANCEL = "APPT_CANCEL";

    private SensitiveScene() {
    }
}
