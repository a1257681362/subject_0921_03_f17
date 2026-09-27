package com.evops.rbac.seed;

import com.evops.auth.security.PasswordHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 一人多岗 RBAC 演示账号播种（rbac-1）。
 *
 * <p>multi_ops01 陈晨：身兼【售票岗 BOX_OFFICE】+【检票岗 TICKET_CHECK】两个岗位，
 * 是「一人多岗、角色叠加取并集、菜单按并集装配」的现场验收样本——
 * 不持 STAFF 通岗，因此并集刻意小于通岗（不能建/删座位、建场次），
 * 可清晰看到「两岗都能做、单岗做不了、并集不是交集」。
 *
 * <p>幂等：账号已存在则整体跳过。
 */
@Component
@Order(Integer.MAX_VALUE)
public class RbacSampleDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RbacSampleDataSeeder.class);

    private static final String VENUE = "滨江大剧院";
    private static final String DEMO_PASSWORD = "Venue@2026";

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;

    public RbacSampleDataSeeder(JdbcTemplate jdbc, PasswordHasher passwordHasher) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_admin_user WHERE username = 'multi_ops01'", Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.of(2026, 9, 24, 9, 0);
        // 主岗位列写首个岗位 BOX_OFFICE；真实授权以 t_admin_user_role 两岗并集为准
        jdbc.update("INSERT INTO t_admin_user "
                        + "(username, display_name, venue, role, password_hash, token_version, status, create_time, update_time) "
                        + "VALUES ('multi_ops01','陈晨',?,'BOX_OFFICE',?,0,'ACTIVE',?,?)",
                VENUE, passwordHasher.hash(DEMO_PASSWORD), now, now);
        jdbc.update("INSERT INTO t_admin_user_role (user_id, role_id) "
                + "SELECT u.id, r.id FROM t_admin_user u, t_rbac_role r "
                + "WHERE u.username = 'multi_ops01' AND r.code IN ('BOX_OFFICE','TICKET_CHECK')");

        Long nextUser = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM t_admin_user", Long.class);
        jdbc.execute("ALTER TABLE t_admin_user ALTER COLUMN id RESTART WITH " + nextUser);
        Long nextLink = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM t_admin_user_role", Long.class);
        jdbc.execute("ALTER TABLE t_admin_user_role ALTER COLUMN id RESTART WITH " + nextLink);

        log.info("一人多岗演示账号播种完成：multi_ops01 陈晨（售票岗+检票岗，初始密码 {}）", DEMO_PASSWORD);
    }
}
