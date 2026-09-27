package com.evops.ticket.sensitive;

/**
 * 高危敏感词拦截异常：由统一扫描闸门抛出，继承 {@link IllegalArgumentException}，
 * 复用全局异常处理（HTTP 200 + success=false + 提示语），检票事务随之回滚。
 *
 * <p>提示语只回显词库词与场景，不回显观众原文（原文已在命中留痕表，按权限审计回查）。
 */
public class SensitiveBlockException extends IllegalArgumentException {

    private final String scene;

    public SensitiveBlockException(String scene, String message) {
        super(message);
        this.scene = scene;
    }

    public String getScene() {
        return scene;
    }
}
