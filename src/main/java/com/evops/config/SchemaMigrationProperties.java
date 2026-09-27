package com.evops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Schema 变更管理参数（前缀 evops.schema）。
 *
 * 并跑期（2026-09 Flyway 接管起两天）：Flyway 与旧 schema.sql 同时工作，
 * 每次启动对账两套变更台账，数字一致再切旧逻辑。
 */
@Component
@ConfigurationProperties(prefix = "evops.schema")
public class SchemaMigrationProperties {

    /**
     * 启动时对账 flyway_schema_history 与旧台账 t_evops_schema_version。
     * 并跑观察期保持 true；切旧（spring.sql.init.mode=never）稳定后可置 false。
     */
    private boolean reconcileEnabled = true;

    /**
     * 对账不一致时是否直接让启动失败。
     * 默认 false：仅打印 ERROR 告警、不阻断现场营业；需要在 CI/演练环境强卡时置 true。
     */
    private boolean failOnMismatch = false;

    public boolean isReconcileEnabled() {
        return reconcileEnabled;
    }

    public void setReconcileEnabled(boolean reconcileEnabled) {
        this.reconcileEnabled = reconcileEnabled;
    }

    public boolean isFailOnMismatch() {
        return failOnMismatch;
    }

    public void setFailOnMismatch(boolean failOnMismatch) {
        this.failOnMismatch = failOnMismatch;
    }
}
