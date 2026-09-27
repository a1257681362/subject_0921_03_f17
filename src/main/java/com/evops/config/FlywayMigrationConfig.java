package com.evops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 迁移编排：在 Spring Boot 默认的 migrate 之前插入失败迁移自愈。
 *
 * <p>执行顺序（Spring Boot 保证 Flyway 早于 spring.sql.init）：
 * <pre>
 *   1. FailedMigrationRecovery 清除上一次 success=FALSE 的失败台账行（仅失败行）
 *   2. flyway.migrate()：validate（成功版本 checksum 校验，防脚本被改）
 *      + 从首个未成功版本续跑；半成品对象由 IF NOT EXISTS/MERGE 幂等收敛
 *   3. 旧 schema.sql 并跑空转、双写旧台账
 *   4. SchemaHistoryReconciler 两套台账对账
 * </pre>
 * 因此新环境"建到一半失败"后直接重启即可续建，无需人工 repair/删表；
 * 已成功脚本若被篡改，仍在第 2 步被 checksum 拦截，自愈不掩盖。
 */
@Configuration
public class FlywayMigrationConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationConfig.class);

    @Bean
    public FlywayMigrationStrategy repairFailedThenMigrate(FailedMigrationRecovery recovery) {
        return flyway -> {
            String table = flyway.getConfiguration().getTable();
            int removed = recovery.removeFailedRows(table);
            if (removed > 0) {
                log.warn("Flyway 失败迁移自愈完成：清理 {} 条失败记录后开始续建（成功版本 checksum 校验仍然生效）",
                        removed);
            }
            flyway.migrate();
        };
    }
}
