-- =====================================================================
-- V2 增量：刷新令牌清理索引 + 变更台账双写
-- ---------------------------------------------------------------------
-- AuthTokenCleanupJob 每日 03:17 执行
--   DELETE FROM t_refresh_token WHERE expires_at < ?
-- 存量行增长后全表扫描代价高，按 expires_at 建索引供范围删除定位。
--
-- 并跑期约定：每个 Flyway 版本同时向旧台账 t_evops_schema_version
-- 写一行（version_no = flyway-<版本号>），保证两套变更记录数字一致、
-- 可逐版本对账；观察期结束停掉旧初始化逻辑后，该 MERGE 可保留也可不再新增。
-- =====================================================================

CREATE INDEX IF NOT EXISTS idx_t_refresh_expires ON t_refresh_token (expires_at);

MERGE INTO t_evops_schema_version (version_no, description)
KEY (version_no)
VALUES ('flyway-2', 'Flyway 接管后首个增量：t_refresh_token.expires_at 清理索引');
