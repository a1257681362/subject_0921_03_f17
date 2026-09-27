package com.evops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Flyway 接管过渡期的新旧变更台账对账（并跑两天，数字对得上再切旧逻辑）。
 *
 * 每次启动在 Flyway 迁移与旧 schema.sql 都执行完之后：
 * 1) flyway_schema_history 中每个成功版本（BASELINE 行代表基线 V1，等价于 V1 SQL）
 *    与旧台账 t_evops_schema_version 逐版本核对——
 *    基线 V1 对应 5 个历史标记（base-0/ticket-1/auth-1/checkin-1/agent-1），
 *    V1 之后的每个增量 Vn 必须有 flyway-n 双写行；
 * 2) 反向核对：旧台账里每个 flyway-n 都能在 history 找到成功记录，不许多记；
 * 3) 抽查增量物理对象：V2 引入的索引必须真实存在，防"只记账没改结构"。
 *
 * 不一致默认只打 ERROR 告警、不阻断现场营业；CI/演练可用
 * evops.schema.fail-on-mismatch=true 强卡启动。
 * 观察期结束、spring.sql.init.mode 置 never 后可关闭本对账（reconcile-enabled=false）。
 */
@Component
@Order(Integer.MAX_VALUE)
public class SchemaHistoryReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaHistoryReconciler.class);

    /** V1 基线覆盖的手工 SQL 时代历史标记 */
    private static final Set<String> BASELINE_LEGACY_MARKERS = new LinkedHashSet<>(java.util.Arrays.asList(
            "base-0", "ticket-1", "auth-1", "checkin-1", "agent-1"));

    /** 各增量版本应落地的物理对象抽查（index_name 按 H2/MySQL 大写惯例比较） */
    private static final List<String> V2_EXPECTED_INDEXES = java.util.Arrays.asList(
            "IDX_T_REFRESH_EXPIRES");

    private static final List<String> V3_EXPECTED_INDEXES = java.util.Arrays.asList(
            "IDX_T_ATTACH_TICKET");

    private static final List<String> V4_EXPECTED_INDEXES = java.util.Arrays.asList(
            "IDX_NOTIFY_TPL_EVENT", "IDX_NOTIFY_REC_RETRY", "IDX_INBOX_STAFF");

    private static final List<String> V5_EXPECTED_INDEXES = java.util.Arrays.asList(
            "IDX_MASK_RULE_STATUS", "IDX_SENSLOG_USER", "IDX_SENSLOG_BIZ");

    private static final List<String> V7_EXPECTED_INDEXES = java.util.Arrays.asList(
            "IDX_SENSITIVE_WORD_STATUS", "IDX_SENSHIT_TIME", "IDX_SENSHIT_WORD", "IDX_SENSHIT_BIZ");

    private final JdbcTemplate jdbc;
    private final SchemaMigrationProperties properties;

    public SchemaHistoryReconciler(JdbcTemplate jdbc, SchemaMigrationProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isReconcileEnabled()) {
            log.debug("Schema 台账对账已关闭（evops.schema.reconcile-enabled=false）");
            return;
        }
        // Flyway 在 H2 MySQL 模式下历史表为带引号小写名，默认模式为大写；取真实存储名再引用
        String historyTable = resolveTableName("flyway_schema_history");
        if (historyTable == null) {
            log.warn("未找到 flyway_schema_history，跳过台账对账（Flyway 是否被关闭？）");
            return;
        }

        List<String> mismatches = new ArrayList<>();

        // history 成功版本（含 BASELINE），按版本号去重。
        // 全量拉取在 Java 侧过滤：H2 默认模式下列名大写、MySQL 模式下 Flyway 建为带引号小写，
        // 避免在 SQL 里引用列名的大小写问题（JdbcTemplate 的列 Map 本身大小写不敏感）。
        List<java.util.Map<String, Object>> historyRows = jdbc.queryForList(
                "SELECT * FROM \"" + historyTable + "\"");
        Set<Integer> successVersions = new LinkedHashSet<>();
        Set<Integer> failedVersions = new LinkedHashSet<>();
        for (java.util.Map<String, Object> row : historyRows) {
            Object versionValue = row.get("version");
            if (versionValue == null) {
                // schema 历史表的 << Flyway Schema Creation >> 占位行无版本号
                continue;
            }
            Integer v = Integer.valueOf(String.valueOf(versionValue));
            if (Boolean.TRUE.equals(row.get("success"))) {
                successVersions.add(v);
            } else {
                failedVersions.add(v);
            }
        }
        failedVersions.forEach(v -> mismatches.add("flyway_schema_history 存在失败版本 V" + v));

        // 旧台账全部标记
        Set<String> ledger = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT version_no FROM t_evops_schema_version", String.class));

        // 1) 基线 V1：5 个历史标记必须齐全
        if (successVersions.contains(1)) {
            for (String marker : BASELINE_LEGACY_MARKERS) {
                if (!ledger.contains(marker)) {
                    mismatches.add("旧台账缺少基线 V1 应有的历史标记：" + marker);
                }
            }
        } else {
            mismatches.add("flyway_schema_history 未记录基线 V1（无成功 V1 SQL/BASELINE 行）");
        }

        // 2) V1 之后的每个成功增量：旧台账必须有 flyway-n 双写行
        for (Integer v : successVersions) {
            if (v > 1 && !ledger.contains("flyway-" + v)) {
                mismatches.add("Flyway 已执行 V" + v + "，但旧台账缺少双写标记 flyway-" + v);
            }
        }

        // 3) 反向：旧台账每个 flyway-n 必须有成功的 history 记录
        for (String marker : ledger) {
            if (marker.startsWith("flyway-")) {
                Integer v;
                try {
                    v = Integer.valueOf(marker.substring("flyway-".length()));
                } catch (NumberFormatException e) {
                    mismatches.add("旧台账存在无法识别的 Flyway 标记：" + marker);
                    continue;
                }
                if (!successVersions.contains(v)) {
                    mismatches.add("旧台账记账 " + marker + "，但 flyway_schema_history 无对应成功版本");
                }
            }
        }

        // 4) 增量物理对象抽查（V2 起按版本补充）
        if (successVersions.contains(2)) {
            for (String index : V2_EXPECTED_INDEXES) {
                if (!indexExists(index)) {
                    mismatches.add("V2 已记账但物理索引不存在：" + index);
                }
            }
        }
        if (successVersions.contains(3)) {
            for (String index : V3_EXPECTED_INDEXES) {
                if (!indexExists(index)) {
                    mismatches.add("V3 已记账但物理索引不存在：" + index);
                }
            }
        }
        if (successVersions.contains(4)) {
            for (String index : V4_EXPECTED_INDEXES) {
                if (!indexExists(index)) {
                    mismatches.add("V4 已记账但物理索引不存在：" + index);
                }
            }
        }
        if (successVersions.contains(5)) {
            for (String index : V5_EXPECTED_INDEXES) {
                if (!indexExists(index)) {
                    mismatches.add("V5 已记账但物理索引不存在：" + index);
                }
            }
        }
        if (successVersions.contains(7)) {
            for (String index : V7_EXPECTED_INDEXES) {
                if (!indexExists(index)) {
                    mismatches.add("V7 已记账但物理索引不存在：" + index);
                }
            }
        }

        int flywayCount = successVersions.size();
        int legacyCount = ledger.size();
        if (mismatches.isEmpty()) {
            log.info("Schema 台账对账一致：Flyway 成功版本 {} 个 {}，旧台账标记 {} 个 {}；增量物理对象抽查通过。"
                            + "并跑观察期结束后可将 spring.sql.init.mode 置 never 切断旧逻辑。",
                    flywayCount, sortedVersions(successVersions),
                    legacyCount, ledger);
        } else {
            mismatches.forEach(m -> log.error("Schema 台账对账不一致：{}", m));
            log.error("Schema 台账对账共 {} 项不一致（Flyway 版本数 {} / 旧台账标记数 {}），"
                            + "请人工核对后再考虑切旧逻辑！",
                    mismatches.size(), flywayCount, legacyCount);
            if (properties.isFailOnMismatch()) {
                throw new IllegalStateException("Schema 台账对账失败：" + mismatches);
            }
        }
    }

    /** @return 表在 INFORMATION_SCHEMA 中的真实存储名；不存在返回 null */
    private String resolveTableName(String logicalName) {
        List<String> names = jdbc.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = ?",
                String.class, logicalName.toLowerCase());
        return names.isEmpty() ? null : names.get(0);
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = ?",
                Integer.class, indexName.toLowerCase());
        return count != null && count > 0;
    }

    private List<Integer> sortedVersions(Set<Integer> versions) {
        List<Integer> list = new ArrayList<>(versions);
        java.util.Collections.sort(list);
        return list;
    }
}
