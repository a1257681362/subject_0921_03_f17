package com.evops.ticket.sensitive;

/**
 * 敏感词级别与处置口径。
 * <ul>
 *   <li>{@link #HIGH} 高危：命中即拒绝业务（检票/退票/作废/排期备注不允许落库），原操作整体失败；</li>
 *   <li>{@link #LOW} 低危：放行业务，但命中片段落库前打码（* 逐码点替换），命中留痕仍存原文与位置。</li>
 * </ul>
 */
public enum SensitiveLevel {

    /** 高危：拦截（阻断本次操作）。 */
    HIGH("高危", true),
    /** 低危：打码后放行。 */
    LOW("低危", false);

    private final String label;
    private final boolean block;

    SensitiveLevel(String label, boolean block) {
        this.label = label;
        this.block = block;
    }

    public String getLabel() {
        return label;
    }

    /** 是否阻断业务。 */
    public boolean isBlock() {
        return block;
    }

    /** 容错解析：非法值回退 LOW（宁打码不误杀业务），由调用方决定是否告警。 */
    public static SensitiveLevel safeValueOf(String code) {
        if (code != null) {
            String trimmed = code.trim().toUpperCase();
            for (SensitiveLevel level : values()) {
                if (level.name().equals(trimmed)) {
                    return level;
                }
            }
        }
        return LOW;
    }
}
