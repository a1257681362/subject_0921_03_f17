package com.evops.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationType;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway 接管方案关键场景验证：
 * 1) 新环境：空库 V1->V2 一键建到最新，结构与台账齐全；
 * 2) 旧环境：先执行手工时代 schema.sql（模拟现场库），再 baseline-on-migrate ——
 *    V1 只记基线不重放，V2 增量执行，原有数据与台账不动；
 * 3) 重复启动：二次 migrate 无待执行版本，幂等无副作用。
 */
class FlywayMigrationTest {

    private DataSource newMemDb(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUsername("sa");
        return ds;
    }

    /**
     * Flyway 在 H2 MySQL 模式下把历史表建成带引号小写名（flyway_schema_history），
     * 默认模式下为大写；从元数据取真实存储名并加引号引用，两种模式通用。
     */
    private String quotedTable(JdbcTemplate jdbc, String logicalName) {
        String actual = jdbc.queryForObject(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = ?",
                String.class, logicalName.toLowerCase());
        assertNotNull(actual, "表不存在：" + logicalName);
        return "\"" + actual + "\"";
    }

    private Flyway flyway(DataSource ds) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    @Test
    void freshDatabase_buildsToLatestInOneShot() {
        DataSource ds = newMemDb("flyway-fresh");
        Flyway flyway = flyway(ds);
        flyway.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 全部业务表落地（H2 MySQL 模式下标识符存小写，用 LOWER 比较以兼容两种模式）
        for (String table : new String[]{
                "t_seat", "t_show_session", "t_sale_order", "t_ticket",
                "t_session_seat_occupy", "t_ticket_status_flow", "t_ticket_no_alloc",
                "t_idempotent_request", "t_admin_user", "t_refresh_token",
                "t_token_blacklist", "t_checkin_appointment",
                "t_agent_message", "t_agent_parse_error",
                "t_ticket_attachment",
                "t_notify_template", "t_notify_recipient",
                "t_notify_dispatch", "t_notify_record", "t_inbox_message",
                "t_mask_rule_version", "t_sensitive_view_log",
                "t_rbac_role", "t_admin_user_role", "t_rbac_menu",
                "t_rbac_role_menu", "t_rbac_deny_log",
                "t_sensitive_word", "t_sensitive_hit_log",
                "t_evops_schema_version", "flyway_schema_history"}) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = ?",
                    Integer.class, table);
            assertNotNull(c);
            assertEquals(1, c, "新库应一键建出表：" + table);
        }

        // history：V1/V2 均为成功的 SQL 迁移（测试库为 H2 MySQL 模式，
        // Flyway 的历史表/列均为带引号小写名）
        String historyTable = quotedTable(jdbc, "flyway_schema_history");
        List<String> versions = jdbc.queryForList(
                "SELECT \"version\" FROM " + historyTable
                        + " WHERE \"version\" IS NOT NULL AND \"success\" = TRUE "
                        + "ORDER BY \"installed_rank\"",
                String.class);
        assertEquals(java.util.Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8"), versions);

        // V2 物理对象真实存在
        Integer idx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = 'idx_t_refresh_expires'",
                Integer.class);
        assertEquals(1, idx);

        // V3 物理对象真实存在
        Integer idxV3 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = 'idx_t_attach_ticket'",
                Integer.class);
        assertEquals(1, idxV3);

        // V4 物理对象真实存在
        for (String idxV4 : new String[]{"idx_notify_tpl_event", "idx_notify_rec_retry", "idx_inbox_staff"}) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = ?",
                    Integer.class, idxV4);
            assertEquals(1, c, "V4 索引应存在：" + idxV4);
        }

        // V5 物理对象真实存在
        for (String idxV5 : new String[]{"idx_mask_rule_status", "idx_senslog_user", "idx_senslog_biz"}) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = ?",
                    Integer.class, idxV5);
            assertEquals(1, c, "V5 索引应存在：" + idxV5);
        }

        // V6 物理对象真实存在 + 内置角色/菜单种子
        for (String idxV6 : new String[]{"idx_user_role_role", "idx_rbac_menu_parent",
                "idx_rbac_rolemenu_menu", "idx_denylog_user", "idx_denylog_time"}) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = ?",
                    Integer.class, idxV6);
            assertEquals(1, c, "V6 索引应存在：" + idxV6);
        }

        // V7 物理对象真实存在 + 分级词库/菜单种子
        for (String idxV7 : new String[]{"idx_sensitive_word_status", "idx_senshit_time",
                "idx_senshit_word", "idx_senshit_biz"}) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = ?",
                    Integer.class, idxV7);
            assertEquals(1, c, "V7 索引应存在：" + idxV7);
        }
        Integer wordRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_sensitive_word WHERE status = 'ENABLED'", Integer.class);
        assertEquals(22, wordRows, "V7 应播种 22 个生效敏感词");
        Integer highWords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_sensitive_word WHERE level = 'HIGH'", Integer.class);
        assertEquals(12, highWords, "V7 应播种 12 个高危词");
        // 全角/大小写变体的归一形态：词库键一律半角小写
        Integer addV = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_sensitive_word WHERE normalized_word = '加v'", Integer.class);
        assertEquals(1, addV);
        Integer wordMenus = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_rbac_menu WHERE id IN (250,251,252)", Integer.class);
        assertEquals(3, wordMenus, "V7 应新增 3 个敏感词管理菜单/权限点");
        Integer wordMenuGrants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_rbac_role_menu WHERE role_id = 1 AND menu_id IN (250,251,252)",
                Integer.class);
        assertEquals(3, wordMenuGrants, "敏感词管理菜单应授给 ADMIN");
        // 种子显式占用 id=22 后，IDENTITY 序列拨到 23：管理端新增词首个自增 id 应为 23
        jdbc.update("INSERT INTO t_sensitive_word (word, normalized_word, level, status, create_time) "
                + "VALUES ('试词XYZ', '试词xyz', 'LOW', 'ENABLED', CURRENT_TIMESTAMP)");
        Integer nextWordId = jdbc.queryForObject(
                "SELECT id FROM t_sensitive_word WHERE normalized_word = '试词xyz'", Integer.class);
        assertEquals(23, nextWordId, "种子词 id 拨到 22 后自增序列应从 23 开始");
        Integer roleRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_rbac_role", Integer.class);
        assertEquals(5, roleRows, "V6 应播种 5 个内置/业务角色");
        Integer adminRole = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_rbac_role WHERE code = 'ADMIN' AND builtin = 1", Integer.class);
        assertEquals(1, adminRole);
        Integer menuRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_rbac_menu", Integer.class);
        assertTrue(menuRows != null && menuRows >= 25, "V6 应播种菜单/权限点，实际：" + menuRows);
        Integer adminMenuGrants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_rbac_role_menu rm JOIN t_rbac_role r ON r.id = rm.role_id "
                        + "WHERE r.code = 'ADMIN'", Integer.class);
        assertEquals(menuRows, adminMenuGrants, "ADMIN 应授全部菜单/权限点");

        // 旧台账：V1 的 5 个历史标记 + V2~V8 双写
        List<String> ledger = jdbc.queryForList(
                "SELECT version_no FROM t_evops_schema_version ORDER BY id", String.class);
        assertTrue(ledger.containsAll(java.util.Arrays.asList(
                "base-0", "ticket-1", "auth-1", "checkin-1", "agent-1",
                "flyway-2", "flyway-3", "flyway-4", "flyway-5", "flyway-6", "flyway-7",
                "flyway-8")));

        // V8：t_refresh_token 新增配对访问令牌登记列
        for (String colV8 : new String[]{"access_jti", "access_expires_at"}) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE LOWER(TABLE_NAME) = 't_refresh_token' AND LOWER(COLUMN_NAME) = ?",
                    Integer.class, colV8);
            assertEquals(1, c, "V8 列应存在：" + colV8);
        }

        // V5 初始规则版本已播种
        Integer ruleRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_mask_rule_version WHERE rule_version = 1 AND status = 'ACTIVE'",
                Integer.class);
        assertEquals(1, ruleRows);

        // 回归：种子显式占用 id=1 后，IDENTITY 序列必须已拨到 2，
        // 首次发布新版本（不显式给 id）自增 id 应为 2，不能撞主键。
        jdbc.update("INSERT INTO t_mask_rule_version "
                + "(rule_version, rule_code, field_rules, whitelist_roles, status, biz_time, create_time) "
                + "VALUES (99, 'CHECKIN_DEFAULT', '{}', '', 'SUPERSEDED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        Integer nextId = jdbc.queryForObject(
                "SELECT id FROM t_mask_rule_version WHERE rule_version = 99", Integer.class);
        assertEquals(2, nextId, "种子 id=1 后自增序列应从 2 开始");

        // 二次启动：无待执行版本
        MigrationInfo[] pending = flyway.info().pending();
        assertEquals(0, pending.length);
    }

    @Test
    void legacyDatabase_baselinesAtV1AndAppliesOnlyV2() throws Exception {
        DataSource ds = newMemDb("flyway-legacy");
        // 模拟现场旧库：手工执行接管前的 schema.sql
        try (Connection conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 接管前不存在 flyway 表，旧台账 5 个历史标记
        Integer flywayTableBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'flyway_schema_history'",
                Integer.class);
        assertEquals(0, flywayTableBefore);
        Integer ledgerBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_evops_schema_version", Integer.class);
        assertEquals(5, ledgerBefore);
        // V2 索引接管前不存在
        Integer idxBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = 'idx_t_refresh_expires'",
                Integer.class);
        assertEquals(0, idxBefore);

        // 首飞：V1 记基线，只执行 V2
        // 模拟现场库里已存在的管理员账号（schema.sql 只建表不灌账号，这里补一行存量数据）
        jdbc.update("INSERT INTO t_admin_user (username, display_name, role, password_hash, status) "
                + "VALUES ('venue_admin', '王芳', 'ADMIN', 'x', 'ACTIVE')");
        Flyway flyway = flyway(ds);
        flyway.migrate();

        // V1 行为类型 BASELINE（不是重放 SQL），V2 为 SQL
        String historyTable = quotedTable(jdbc, "flyway_schema_history");
        String v1Type = jdbc.queryForObject(
                "SELECT \"type\" FROM " + historyTable + " WHERE \"version\" = '1'", String.class);
        assertEquals(MigrationType.BASELINE.name(), v1Type);
        String v2Type = jdbc.queryForObject(
                "SELECT \"type\" FROM " + historyTable + " WHERE \"version\" = '2'", String.class);
        assertEquals(MigrationType.SQL.name(), v2Type);
        Boolean v2Success = jdbc.queryForObject(
                "SELECT \"success\" FROM " + historyTable + " WHERE \"version\" = '2'", Boolean.class);
        assertTrue(v2Success);

        // V2~V8 增量落地；旧台账保留 5 条并新增双写 7 条
        Integer idxAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = 'idx_t_refresh_expires'",
                Integer.class);
        assertEquals(1, idxAfter);
        // V8 列在老库上由 ALTER TABLE ADD COLUMN IF NOT EXISTS 增量补齐
        for (String colV8 : new String[]{"access_jti", "access_expires_at"}) {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                            + "WHERE LOWER(TABLE_NAME) = 't_refresh_token' AND LOWER(COLUMN_NAME) = ?",
                    Integer.class, colV8);
            assertEquals(1, c, "老库升级后 V8 列应存在：" + colV8);
        }
        List<String> ledger = jdbc.queryForList(
                "SELECT version_no FROM t_evops_schema_version", String.class);
        assertEquals(12, ledger.size());
        assertTrue(ledger.contains("flyway-2"));
        assertTrue(ledger.contains("flyway-3"));
        assertTrue(ledger.contains("flyway-4"));
        assertTrue(ledger.contains("flyway-5"));
        assertTrue(ledger.contains("flyway-6"));
        assertTrue(ledger.contains("flyway-7"));
        assertTrue(ledger.contains("flyway-8"));
        assertTrue(ledger.containsAll(java.util.Arrays.asList(
                "base-0", "ticket-1", "auth-1", "checkin-1", "agent-1")));

        // 老库升级：V6 按存量 t_admin_user.role 回填多岗关联（venue_admin 绑到 ADMIN 角色）
        Integer adminLink = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_admin_user_role ur "
                        + "JOIN t_admin_user u ON u.id = ur.user_id "
                        + "JOIN t_rbac_role r ON r.id = ur.role_id "
                        + "WHERE u.username = 'venue_admin' AND r.code = 'ADMIN'", Integer.class);
        assertEquals(1, adminLink, "老库 venue_admin 应回填 ADMIN 岗位");

        // 再次启动幂等
        flyway.migrate();
        assertEquals(0, flyway.info().pending().length);
        Integer ledgerFinal = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_evops_schema_version", Integer.class);
        assertEquals(12, ledgerFinal);
    }

    @Test
    void repeatMigrate_isIdempotent() {
        DataSource ds = newMemDb("flyway-repeat");
        Flyway flyway = flyway(ds);
        flyway.migrate();
        flyway.migrate();
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    // ==================== 失败自愈 / 基线完整性（2026-09 新增） ====================

    /**
     * 真实版本脚本（V1~V8）复制到临时目录，另加一个"建到一半失败"的 V9：
     * 先成功 CREATE 一个对象，随后引用不存在的表报错。
     */
    private static final String[] REAL_MIGRATIONS = {
            "V1__baseline_schema.sql", "V2__refresh_token_expires_index.sql",
            "V3__ticket_attachment.sql", "V4__notify_checkin.sql",
            "V5__mask_sensitive.sql", "V6__rbac_multi_role.sql",
            "V7__sensitive_word.sql", "V8__refresh_revoke_old_access.sql"};

    private static final String BROKEN_V9 =
            "CREATE TABLE IF NOT EXISTS t_repro_self_heal (id BIGINT PRIMARY KEY);\n"
            + "-- 中途引用不存在的表，模拟新环境初始化建到一半失败\n"
            + "INSERT INTO t_table_that_never_exists (x) VALUES (1);\n"
            + "MERGE INTO t_evops_schema_version (version_no, description) KEY (version_no) "
            + "VALUES ('flyway-9', 'broken');\n";

    private static final String FIXED_V9 =
            "CREATE TABLE IF NOT EXISTS t_repro_self_heal (id BIGINT PRIMARY KEY);\n"
            + "CREATE INDEX IF NOT EXISTS idx_repro_self_heal_id ON t_repro_self_heal (id);\n"
            + "MERGE INTO t_evops_schema_version (version_no, description) KEY (version_no) "
            + "VALUES ('flyway-9', 'self-healed');\n";

    private Path copyRealMigrations(Path dir) throws Exception {
        for (String name : REAL_MIGRATIONS) {
            copyClasspathResource(name, dir);
        }
        return dir;
    }

    /** 把 classpath 上的迁移脚本复制到临时目录，返回目标文件。 */
    private Path copyClasspathResource(String name, Path dir) throws Exception {
        Path target = dir.resolve(name);
        try (java.io.InputStream in = new ClassPathResource("db/migration/" + name).getInputStream()) {
            Files.copy(in, target);
        }
        return target;
    }

    private Flyway flywayAt(DataSource ds, Path dir) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("filesystem:" + dir.toAbsolutePath())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load();
    }

    private int failedRows(JdbcTemplate jdbc) {
        String t = quotedTable(jdbc, "flyway_schema_history");
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM " + t + " WHERE \"success\" = FALSE",
                Integer.class);
        return c == null ? 0 : c;
    }

    private int successRows(JdbcTemplate jdbc) {
        String t = quotedTable(jdbc, "flyway_schema_history");
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM " + t + " WHERE \"success\" = TRUE",
                Integer.class);
        return c == null ? 0 : c;
    }

    /**
     * 核心场景：新环境 V9 建到一半失败（DDL 半成品残留 + history 失败行）。
     * 模拟重启：FailedMigrationRecovery 清失败行 → 修好脚本 → 再 migrate，
     * 必须靠 IF NOT EXISTS/MERGE 幂等收敛，不报"对象已存在"，台账与实际结构一致。
     */
    @Test
    void midwayFailure_restartSelfHeals_structureAndVersionReconcile(@TempDir Path dir) throws Exception {
        copyRealMigrations(dir);
        Files.write(dir.resolve("V9__self_heal.sql"), BROKEN_V9.getBytes(StandardCharsets.UTF_8));

        DataSource ds = newMemDb("flyway-selfheal");
        Flyway flyway = flywayAt(ds, dir);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 首跑：V1~V8 成功，V9 中途失败
        assertThrows(FlywayException.class, flyway::migrate);
        assertEquals(1, failedRows(jdbc), "V9 应留一条 success=FALSE 失败行");
        Integer halfObject = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 't_repro_self_heal'",
                Integer.class);
        assertEquals(1, halfObject, "H2 DDL 不回滚：失败前建出的半成品表应残留");

        // 不处理直接重跑：被失败行拦在 validate（复现现场"对象已存在/卡住"的根因）
        assertThrows(FlywayException.class, flyway::migrate);

        // 自愈第 1 步：只删失败行，成功行（V1~V8，含 BASELINE）一行不动
        FailedMigrationRecovery recovery = new FailedMigrationRecovery(ds);
        int successBefore = successRows(jdbc);
        int removed = recovery.removeFailedRows("flyway_schema_history");
        assertEquals(1, removed);
        assertEquals(0, failedRows(jdbc));
        assertEquals(successBefore, successRows(jdbc), "自愈绝不能删除任何成功版本行");

        // 自愈不掩盖真实脚本缺陷：脚本仍是坏的，再跑必须在同一语句再次失败
        assertThrows(FlywayException.class, flyway::migrate);
        assertEquals(1, failedRows(jdbc), "坏脚本重跑应再次落失败行，而不是被粉饰为成功");
        recovery.removeFailedRows("flyway_schema_history");

        // 自愈第 2 步：修正失败版本脚本（失败版本本就允许修正，未锁 checksum），重启续建
        Files.write(dir.resolve("V9__self_heal.sql"), FIXED_V9.getBytes(StandardCharsets.UTF_8));
        flywayAt(ds, dir).migrate(); // 不得抛"对象已存在"：CREATE IF NOT EXISTS 对残留半成品空转收敛

        // 结构与台账完全一致：V9 成功、物理对象齐全、旧台账双写到位、无失败行
        assertEquals(0, failedRows(jdbc));
        String historyTable = quotedTable(jdbc, "flyway_schema_history");
        String v9Success = jdbc.queryForObject(
                "SELECT \"type\" FROM " + historyTable + " WHERE \"version\" = '9'", String.class);
        assertEquals(MigrationType.SQL.name(), v9Success);
        Integer idx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES WHERE LOWER(INDEX_NAME) = 'idx_repro_self_heal_id'",
                Integer.class);
        assertEquals(1, idx, "修复后续建应补上 V9 索引");
        List<String> ledger = jdbc.queryForList(
                "SELECT version_no FROM t_evops_schema_version WHERE version_no = 'flyway-9'",
                String.class);
        assertEquals(1, ledger.size(), "旧台账必须有 flyway-9 双写，版本记录与结构对得上");

        // 再启动：无待执行版本，完全幂等
        assertEquals(0, flywayAt(ds, dir).info().pending().length);
        jdbc.execute("DROP TABLE t_repro_self_heal");
    }

    /** 自愈在没有失败行时必须是空操作：成功版本行/checksum 一律不动。 */
    @Test
    void recovery_isNoopWhenAllSucceeded(@TempDir Path dir) throws Exception {
        copyRealMigrations(dir);
        DataSource ds = newMemDb("flyway-noop");
        Flyway flyway = flywayAt(ds, dir);
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        int successBefore = successRows(jdbc);

        assertEquals(0, new FailedMigrationRecovery(ds).removeFailedRows("flyway_schema_history"));
        assertEquals(successBefore, successRows(jdbc), "无失败行时成功版本行数不得变化");
        assertEquals(0, flyway.info().pending().length);
    }

    /**
     * 已执行脚本防改（checksum 基线完整性）：V1~V8 成功后篡改 V2，
     * 下一次启动必须被 validate 以 checksum mismatch 拦截；自愈不得帮忙删除成功行绕过。
     */
    @Test
    void appliedScriptTampered_isBlockedByChecksum_andRecoveryKeepsSuccessRow(@TempDir Path dir)
            throws Exception {
        copyRealMigrations(dir);
        DataSource ds = newMemDb("flyway-tamper");
        Flyway flyway = flywayAt(ds, dir);
        flyway.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        int successBefore = successRows(jdbc);

        // 篡改已成功执行的 V2（追加一条语句）
        Path v2 = dir.resolve("V2__refresh_token_expires_index.sql");
        List<String> lines = Files.readAllLines(v2, StandardCharsets.UTF_8);
        lines.add("ALTER TABLE t_refresh_token ADD COLUMN tampered_col VARCHAR(8);");
        Files.write(v2, lines);

        FlywayValidateException ex = assertThrows(FlywayValidateException.class,
                () -> flywayAt(ds, dir).migrate());
        assertTrue(ex.getMessage().contains("checksum mismatch"),
                "篡改已执行脚本必须报 checksum mismatch，实际：" + ex.getMessage());
        // 自愈只认 success=FALSE：成功行一个不删，checksum 防线不被绕过
        assertEquals(0, new FailedMigrationRecovery(ds).removeFailedRows("flyway_schema_history"));
        assertEquals(successBefore, successRows(jdbc), "被篡改的成功版本行必须保留以持续拦截启动");
        Integer tampered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE LOWER(TABLE_NAME)='t_refresh_token' AND LOWER(COLUMN_NAME)='tampered_col'",
                Integer.class);
        assertEquals(0, tampered, "validate 在执行前拦截，被篡改脚本里的语句不得落地");
    }

    /**
     * 存量数据安全：V1~V8 成功后写入一条真实业务数据，再遭遇 V9 失败 + 自愈续建，
     * 老数据必须原样保留（修新场景不能坏存量）。
     */
    @Test
    void midwayFailureSelfHeal_preservesExistingBusinessData(@TempDir Path dir) throws Exception {
        copyRealMigrations(dir);
        Files.write(dir.resolve("V9__self_heal.sql"), BROKEN_V9.getBytes(StandardCharsets.UTF_8));
        DataSource ds = newMemDb("flyway-olddata");
        Flyway flyway = flywayAt(ds, dir);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertThrows(FlywayException.class, flyway::migrate);

        // 失败发生在 V9，之前已提交版本的存量数据必须还在且可读
        jdbc.update("INSERT INTO t_admin_user (username, display_name, role, password_hash, status) "
                + "VALUES ('legacy_stage', '老场务', 'STAGE_STAFF', 'x', 'ACTIVE')");

        new FailedMigrationRecovery(ds).removeFailedRows("flyway_schema_history");
        Files.write(dir.resolve("V9__self_heal.sql"), FIXED_V9.getBytes(StandardCharsets.UTF_8));
        flywayAt(ds, dir).migrate();

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_admin_user WHERE username='legacy_stage' AND role='STAGE_STAFF'",
                Integer.class);
        assertEquals(1, rows, "自愈续建不得损坏/丢失存量业务数据");
        jdbc.execute("DROP TABLE t_repro_self_heal");
    }
}
