package com.evops.ticket.enums;

/**
 * 票状态流水类型。REFUND/VOID 为反向记录（reverseFlag=1）。
 */
public enum FlowType {
    ISSUE(false),
    CHECK(false),
    REFUND(true),
    VOID(true);

    private final boolean reverse;

    FlowType(boolean reverse) {
        this.reverse = reverse;
    }

    public int reverseFlag() {
        return reverse ? 1 : 0;
    }
}
