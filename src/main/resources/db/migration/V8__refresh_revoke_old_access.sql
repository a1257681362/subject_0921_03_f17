-- =====================================================================
-- V8 增量：刷新轮换时作废旧访问令牌（auth-1 缺陷修复）
-- ---------------------------------------------------------------------
-- 背景：/api/auth/refresh 只把旧刷新令牌 ACTIVE→USED 并沿家族签发新对，
-- 配对的旧访问令牌（JWT，15 分钟内有效）没有任何吊销动作——签名合法、
-- tv 不变、不在黑名单，旧 access 在整个剩余有效期内照样 200，
-- 新旧两端各持一枚有效 access 并行操作，"轮换"形同虚设。
--
-- 修复：刷新令牌存证登记其配对访问令牌的 jti 与自然过期时间；
-- 轮换消费成功的同一事务内，把该 jti 写入 t_token_blacklist
-- （reason=ROTATED），旧 access 下一请求即在黑名单环节被挡（401）。
-- 只联动被轮换的这一台设备（family），其他设备家族不受影响。
-- 存量行两列为 NULL（修复前登录，无配对登记），轮换时跳过拉黑，
-- 待其自然过期/重新登录后收敛，不破坏并跑期老库。
-- =====================================================================

ALTER TABLE t_refresh_token ADD COLUMN IF NOT EXISTS access_jti VARCHAR(64);
ALTER TABLE t_refresh_token ADD COLUMN IF NOT EXISTS access_expires_at TIMESTAMP;

MERGE INTO t_evops_schema_version (version_no, description)
KEY (version_no)
VALUES ('flyway-8', '修复：刷新轮换登记并拉黑配对旧访问令牌，旧 access 立即失效');
