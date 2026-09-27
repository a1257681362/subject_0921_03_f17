package com.evops.ticket.notify.seed;

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
 * 检票通知样本播种（独立幂等，老库升级到 V4 后启动也会补齐）。
 *
 * 账号：新增场务岗 stage01 周洋（与现有账号同一套 PBKDF2 口令 Venue@2026）；
 * 收件人：周洋（场务，工号+邮箱）、王芳（值班经理，复用 venue_admin 工号+邮箱）；
 * 模板：TICKET_CHECK 事件的站内信/邮件各一套，含票号/场次/座位/检票时间等变量。
 *
 * 全部按业务键 MERGE/判存在，不依赖全量样本是否已播种，可单独重复执行。
 */
@Component
@Order(Integer.MAX_VALUE - 5)
public class NotifySampleDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NotifySampleDataSeeder.class);

    private static final String VENUE = "滨江大剧院";
    private static final String DEMO_PASSWORD = "Venue@2026";

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;

    public NotifySampleDataSeeder(JdbcTemplate jdbc, PasswordHasher passwordHasher) {
        this.jdbc = jdbc;
        this.passwordHasher = passwordHasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedStageUser();
        seedRecipients();
        seedTemplates();
    }

    /** 场务岗账号（站内信需登录后台查收）；存在则跳过。 */
    private void seedStageUser() {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_admin_user WHERE username = 'stage01'", Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.of(2026, 9, 23, 9, 0);
        jdbc.update("INSERT INTO t_admin_user "
                        + "(username, display_name, venue, role, password_hash, token_version, status, create_time, update_time) "
                        + "VALUES ('stage01','周洋',?,'STAGE_STAFF',?,0,'ACTIVE',?,?)",
                VENUE, passwordHasher.hash(DEMO_PASSWORD), now, now);
        // 一人多岗：stage01 → STAGE_STAFF（新库由播种补齐；老库由 V6 迁移回填，幂等）
        jdbc.update("INSERT INTO t_admin_user_role (user_id, role_id) "
                + "SELECT u.id, r.id FROM t_admin_user u, t_rbac_role r "
                + "WHERE u.username = 'stage01' AND r.code = 'STAGE_STAFF' "
                + "AND NOT EXISTS (SELECT 1 FROM t_admin_user_role ur WHERE ur.user_id = u.id AND ur.role_id = r.id)");
        Long next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM t_admin_user", Long.class);
        jdbc.execute("ALTER TABLE t_admin_user ALTER COLUMN id RESTART WITH " + next);
        log.info("场务样本账号播种完成：stage01 周洋（初始密码 {}）", DEMO_PASSWORD);
    }

    private void seedRecipients() {
        Object[][] rows = {
                {"周洋", "stage01", "zhouyang.stage@example.cn", "STAGE_STAFF", "前场场务，检票关键节点接收人"},
                {"王芳", "venue_admin", "wangfang.manager@example.cn", "DUTY_MANAGER", "值班经理，检票节点抄送"},
        };
        int added = 0;
        for (Object[] row : rows) {
            Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_notify_recipient WHERE staff_no = ? AND deleted = 0",
                    Integer.class, row[1]);
            if (exists != null && exists > 0) {
                continue;
            }
            jdbc.update("INSERT INTO t_notify_recipient "
                            + "(recipient_name, staff_no, email, role_code, enabled, status, remark, "
                            + "operator, request_id, biz_time, version, deleted, create_time, update_time) "
                            + "VALUES (?,?,?,?,1,'ACTIVE',?,'venue_admin','SEED-NOTIFY-RECIPIENT',?,0,0,?,?)",
                    row[0], row[1], row[2], row[3], row[4],
                    LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
            added++;
        }
        if (added > 0) {
            log.info("检票通知收件人样本播种完成：{} 个（场务+值班经理，均工号+邮箱双通道可达）", added);
        }
    }

    private void seedTemplates() {
        String inboxTitle = "【检票提醒】${showName} ${ticketNo} 已检票入场";
        String inboxBody = "场务您好：\n"
                + "${checkTime} 有观众检票入场，请关注现场秩序与座次引导。\n"
                + "演出：${showName}（场次 ${sessionCode}），开演时间 ${showTime}\n"
                + "场馆：${venue}\n"
                + "座位：${area} ${rowNo}排${seatNo}座\n"
                + "票号：${ticketNo}　订单号：${orderNo}\n"
                + "检票人：${operator}　备注：${reason}";

        String emailTitle = "【${venue}检票通知】${showName} ${ticketNo} 已于 ${checkTime} 检票";
        String emailBody = "您好：\n"
                + "${checkTime}，以下演出票完成检票入场，现同步场务值守安排：\n\n"
                + "演出名称：${showName}\n"
                + "场次编号：${sessionCode}（开演 ${showTime}）\n"
                + "场馆：${venue}\n"
                + "座位：${area} ${rowNo}排${seatNo}座\n"
                + "票号：${ticketNo}\n"
                + "订单号：${orderNo}\n"
                + "检票人：${operator}\n"
                + "备注：${reason}\n\n"
                + "本邮件由场馆票务系统检票节点自动发送，请勿直接回复。";

        upsertTemplate("CHECK_STAGE", "检票节点-场务通知", "TICKET_CHECK", "INBOX", inboxTitle, inboxBody);
        upsertTemplate("CHECK_STAGE", "检票节点-场务通知", "TICKET_CHECK", "EMAIL", emailTitle, emailBody);
    }

    private void upsertTemplate(String code, String name, String eventCode, String channel,
                                String title, String body) {
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_notify_template WHERE template_code = ? AND channel = ? AND deleted = 0",
                Integer.class, code, channel);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("INSERT INTO t_notify_template "
                        + "(template_code, template_name, event_code, channel, title_template, body_template, "
                        + "enabled, tpl_version, status, remark, operator, request_id, biz_time, "
                        + "version, deleted, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,1,1,'ACTIVE','检票关键节点通知场务（样本）',"
                        + "'venue_admin','SEED-NOTIFY-TEMPLATE',?,0,0,?,?)",
                code, name, eventCode, channel, title, body,
                LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        log.info("检票通知模板样本播种完成：{} / {}", code, channel);
    }
}
