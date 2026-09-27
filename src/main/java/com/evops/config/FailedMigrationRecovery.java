package com.evops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * Flyway 失败迁移自愈：启动正式 migrate 之前，清掉上一次迁移失败残留在
 * flyway_schema_history 中的 success=FALSE 行（等价于 flyway repair 中
 * "删除失败迁移记录"这一动作，但只删失败行）。
 *
 * <p>背景（H2 文件库实证）：迁移脚本在某条语句中途失败时——
 * <ul>
 *   <li>已执行的 DDL（CREATE TABLE/INDEX）在 H2 上隐式提交、<b>不随事务回滚</b>，
 *       半成品表/索引残留在库里；未提交的 DML 回滚；</li>
 *   <li>history 留一行 success=FALSE；</li>
 *   <li>不处理直接重跑，会在 validate 阶段被 "Detected failed migration to version N"
 *       拦死，脚本里的 IF NOT EXISTS 根本没机会执行，现场只能手工 DROP，
 *       极易造成版本台账与实际结构对不上。</li>
 * </ul>
 *
 * <p>清理失败行后重跑：DDL 全部 IF NOT EXISTS 空转、DML 全部 MERGE /
 * INSERT ... WHERE NOT EXISTS 幂等，半成品库可安全收敛到最新版本——
 * 即"建到一半失败，直接重启即可续建"，无需人工删表。
 *
 * <p><b>红线</b>：本类只删除 success=FALSE 的失败行。已成功版本（含 BASELINE）
 * 一行都不动、checksum 一个字节都不改——篡改已执行脚本仍由 Flyway 的
 * validate-on-migrate 以 checksum mismatch 拦截启动，自愈绝不能掩盖该问题。
 * 脚本本身仍有错时，随后的 migrate 会在同一语句再次失败并让启动失败，
 * 不掩盖任何真实脚本缺陷。
 */
@Component
public class FailedMigrationRecovery {

    private static final Logger log = LoggerFactory.getLogger(FailedMigrationRecovery.class);

    private final JdbcTemplate jdbc;

    public FailedMigrationRecovery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 删除指定 history 表中的失败迁移行。
     *
     * @param historyTable Flyway 配置的历史表逻辑名（默认 flyway_schema_history）
     * @return 删除的失败行数量（全新库无历史表时为 0）
     */
    public int removeFailedRows(String historyTable) {
        String table = resolveStoredName("TABLES", historyTable, null);
        if (table == null) {
            // 全新库：Flyway 尚未建历史表，没有失败行可清
            return 0;
        }
        String rankCol = resolveStoredName("COLUMNS", "installed_rank", table);
        String versionCol = resolveStoredName("COLUMNS", "version", table);
        String descCol = resolveStoredName("COLUMNS", "description", table);
        String scriptCol = resolveStoredName("COLUMNS", "script", table);
        String successCol = resolveStoredName("COLUMNS", "success", table);
        if (rankCol == null || versionCol == null || descCol == null || scriptCol == null || successCol == null) {
            throw new IllegalStateException("Flyway 历史表 \"" + table + "\" 结构不符合预期，"
                    + "缺少 installed_rank/version/description/script/success 列，拒绝自动清理");
        }

        List<Map<String, Object>> failed = jdbc.queryForList(
                "SELECT " + q(rankCol) + " AS rk, " + q(versionCol) + " AS ver, "
                        + q(descCol) + " AS dsc, " + q(scriptCol) + " AS scr "
                        + "FROM " + q(table) + " WHERE " + q(successCol) + " = FALSE");
        if (failed.isEmpty()) {
            return 0;
        }
        for (Map<String, Object> row : failed) {
            Object rank = row.get("rk");
            String version = String.valueOf(row.get("ver"));
            String description = String.valueOf(row.get("dsc"));
            String script = String.valueOf(row.get("scr"));
            // 上一轮失败残留的半成品对象（H2 DDL 不回滚）由本次幂等迁移自行 IF NOT EXISTS 收敛
            int deleted = jdbc.update(
                    "DELETE FROM " + q(table) + " WHERE " + q(rankCol) + " = ?", rank);
            log.warn("检测到上次 Flyway 迁移失败的残留记录（version={} script={} description={}），"
                            + "已删除该失败台账行 {} 条；半成品对象将在本次启动由幂等脚本自动续建，"
                            + "成功版本记录与 checksum 未做任何改动。",
                    version, script, description, deleted);
        }
        return failed.size();
    }

    /**
     * INFORMATION_SCHEMA 中取标识符的真实存储名（H2 MySQL 模式小写、默认模式大写，通用处理）。
     *
     * @param metaTable TABLES / COLUMNS
     * @param logicalName 目标逻辑名（表名；查列时为列名）
     * @param ownerTable 查列时限定所属表的真实存储名；查表时传 null
     */
    private String resolveStoredName(String metaTable, String logicalName, String ownerTable) {
        String nameColumn = "COLUMNS".equals(metaTable) ? "COLUMN_NAME" : "TABLE_NAME";
        StringBuilder sql = new StringBuilder("SELECT ").append(nameColumn)
                .append(" FROM INFORMATION_SCHEMA.").append(metaTable)
                .append(" WHERE LOWER(").append(nameColumn).append(") = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(logicalName.toLowerCase());
        if (ownerTable != null) {
            sql.append(" AND LOWER(TABLE_NAME) = ?");
            args.add(ownerTable.toLowerCase());
        }
        sql.append(" LIMIT 1");
        List<String> names = jdbc.queryForList(sql.toString(), String.class, args.toArray());
        return names.isEmpty() ? null : names.get(0);
    }

    private String q(String identifier) {
        return "\"" + identifier + "\"";
    }
}
