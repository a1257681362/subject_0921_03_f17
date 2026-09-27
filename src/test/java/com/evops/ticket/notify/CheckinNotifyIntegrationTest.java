package com.evops.ticket.notify;

import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.ticket.TicketActionRequest;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.mapper.TicketMapper;
import com.evops.ticket.notify.channel.ChannelSendResult;
import com.evops.ticket.notify.channel.EmailChannelSender;
import com.evops.ticket.notify.dto.TemplateUpsertRequest;
import com.evops.ticket.notify.dto.stats.NotifyStatsVO;
import com.evops.ticket.notify.entity.InboxMessage;
import com.evops.ticket.notify.entity.NotifyDispatch;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.entity.NotifyRecipient;
import com.evops.ticket.notify.entity.NotifyTemplate;
import com.evops.ticket.notify.mapper.InboxMessageMapper;
import com.evops.ticket.notify.mapper.NotifyDispatchMapper;
import com.evops.ticket.notify.mapper.NotifyRecordMapper;
import com.evops.ticket.notify.mapper.NotifyRecipientMapper;
import com.evops.ticket.notify.mapper.NotifyTemplateMapper;
import com.evops.ticket.notify.service.NotifyAdminService;
import com.evops.ticket.notify.service.NotifyRetryJob;
import com.evops.ticket.notify.service.NotifySendService;
import com.evops.ticket.notify.service.NotifyStatsService;
import com.evops.ticket.service.TicketService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 检票通知关键路径走查：
 * 1) 检票事件触发双通道（站内信+邮件）成功，变量渲染正确，统计正确；
 * 2) 发送记录快照渲染内容，事后改模板升版本，已发内容不变；
 * 3) 邮件连续失败 -> 自动重试 2 次（共 3 次投递）后置 FAILED，站内信不受影响（PARTIAL）；
 * 4) 同票重复触发不产生重复通知（批次幂等）；
 * 5) 收件人无邮箱 -> EMAIL 记 SKIPPED，不投递。
 *
 * 用样本数据中的在售 SOLD 票驱动真实检票；同一张内存库被各用例顺序共享。
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CheckinNotifyIntegrationTest {

    @Autowired private TicketService ticketService;
    @Autowired private TicketMapper ticketMapper;
    @Autowired private NotifySendService sendService;
    @Autowired private NotifyStatsService statsService;
    @Autowired private NotifyRecordMapper recordMapper;
    @Autowired private NotifyDispatchMapper dispatchMapper;
    @Autowired private NotifyTemplateMapper templateMapper;
    @Autowired private InboxMessageMapper inboxMapper;
    @Autowired private NotifyRecipientMapper recipientMapper;
    @Autowired private NotifyAdminService adminService;
    @Autowired private JdbcTemplate jdbc;

    /** 桩掉定时任务，测试窗口内不让 cron 改变记录状态 */
    @MockBean
    private NotifyRetryJob retryJob;

    /** 桩掉代理平台出站，避免检票触发平台推送噪音 */
    @MockBean
    private com.evops.ticket.agent.transport.AgentTransportClient agentTransport;

    /** 可控的邮件发送器：默认成功；failForever=true 时持续故障用于验证重试耗尽 */
    @MockBean
    private EmailChannelSender emailChannelSender;

    private boolean failForever = false;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Context("ticket_check01", "notify-test-" + System.nanoTime(), "/test", 0L));
        when(agentTransport.send(any(), any()))
                .thenReturn(new com.evops.ticket.agent.transport.SendResult(200, "OK"));
        failForever = false;
        org.mockito.Mockito.reset(emailChannelSender);
        when(emailChannelSender.channel()).thenReturn("EMAIL");
        when(emailChannelSender.send(any())).thenAnswer(inv -> {
            NotifyRecord r = inv.getArgument(0);
            if (failForever) {
                return ChannelSendResult.fail(500, "模拟邮件网关故障");
            }
            return ChannelSendResult.ok(200, "MID-TEST-" + r.getId());
        });
    }

    @Test
    @Order(1)
    void check_triggersBothChannels_renderedAndPersistedAndStats() {
        LocalDateTime since = LocalDateTime.now().minusSeconds(1);
        String ticketNo = takeFreshSoldTicket();
        Ticket before = findTicket(ticketNo);

        TicketActionRequest req = new TicketActionRequest();
        req.setReason("入场检票");
        ticketService.check(ticketNo, req);

        // 一个批次；样本 2 个收件人均双通道可达 -> 2 收件人 × 2 通道 = 4 条记录，全部成功
        List<NotifyDispatch> dispatches = dispatchesOf(before.getId());
        assertEquals(1, dispatches.size());
        assertEquals("SUCCESS", dispatches.get(0).getStatus());

        List<NotifyRecord> records = recordsOf(ticketNo);
        long inbox = records.stream().filter(r -> "INBOX".equals(r.getChannel())).count();
        long email = records.stream().filter(r -> "EMAIL".equals(r.getChannel())).count();
        assertEquals(2, inbox);
        assertEquals(2, email);
        assertTrue(records.stream().allMatch(r -> "SUCCESS".equals(r.getStatus())), "全部应首发成功");

        NotifyRecord firstInbox = records.stream().filter(r -> "INBOX".equals(r.getChannel())).findFirst().get();
        // 变量渲染：标题/正文含真实票号与场次信息，不含未替换的 ${...}
        assertTrue(firstInbox.getTitleRendered().contains(ticketNo));
        assertTrue(firstInbox.getContentRendered().contains("天鹅湖")
                || firstInbox.getContentRendered().contains("山河令"));
        assertFalse(firstInbox.getContentRendered().contains("${"), "模板变量必须全部渲染");
        assertEquals(1, firstInbox.getTemplateVersion());
        assertNotNull(firstInbox.getVarsSnapshot());
        assertTrue(firstInbox.getVarsSnapshot().contains("ticketNo"));

        // 站内信确实进了场务信箱
        Long inboxRows = inboxMapper.selectCount(new LambdaQueryWrapper<InboxMessage>()
                .eq(InboxMessage::getRecordId, firstInbox.getId()));
        assertEquals(1L, inboxRows);

        // 统计按时间窗只看本用例：批次 1 成功；通道 SUCCESS 计数与记录数一致
        NotifyStatsVO stats = statsService.stats("TICKET_CHECK", since, null);
        assertEquals(1L, stats.getTotalDispatches());
        assertEquals(1L, stats.getSuccessDispatches());
        assertEquals(inbox, stats.getChannels().get("INBOX").get("SUCCESS"));
        assertEquals(email, stats.getChannels().get("EMAIL").get("SUCCESS"));
    }

    @Test
    @Order(5)
    void templateChange_doesNotAffectSentContent_andBumpsVersion() {
        String ticketNo = takeFreshSoldTicket();
        ticketService.check(ticketNo, new TicketActionRequest());

        NotifyRecord snapshot = recordsOf(ticketNo).stream()
                .filter(r -> "EMAIL".equals(r.getChannel())).findFirst().get();
        String oldTitle = snapshot.getTitleRendered();
        String oldBody = snapshot.getContentRendered();
        Integer oldVersion = snapshot.getTemplateVersion();

        // 通过管理服务修改邮件模板文案（走版本自增逻辑）
        NotifyTemplate tpl = templateMapper.selectById(snapshot.getTemplateId());
        TemplateUpsertRequest upsert = new TemplateUpsertRequest();
        upsert.setTemplateCode(tpl.getTemplateCode());
        upsert.setTemplateName(tpl.getTemplateName());
        upsert.setEventCode(tpl.getEventCode());
        upsert.setChannel(tpl.getChannel());
        upsert.setTitleTemplate("【改版通知】${ticketNo} 新版标题");
        upsert.setBodyTemplate("改版后正文：${showName} ${ticketNo} ${checkTime}");
        upsert.setEnabled(true);
        adminService.updateTemplate(tpl.getId(), upsert);

        NotifyTemplate updated = templateMapper.selectById(tpl.getId());
        assertEquals(oldVersion + 1, updated.getTemplateVersion(), "文案变化应升一个版本");

        // 已发记录仍是旧内容、旧版本——模板改动不影响已发内容
        NotifyRecord sent = recordMapper.selectById(snapshot.getId());
        assertEquals(oldTitle, sent.getTitleRendered());
        assertEquals(oldBody, sent.getContentRendered());
        assertEquals(oldVersion, sent.getTemplateVersion());
    }

    @Test
    @Order(4)
    void emailKeepsFailing_retriesTwiceThenFailed_inboxStillSuccess_partial() {
        // 邮件始终故障：首发 + 2 次重试共 3 次投递均失败
        failForever = true;
        String ticketNo = takeFreshSoldTicket();
        ticketService.check(ticketNo, new TicketActionRequest());

        List<NotifyRecord> emails = recordsOf(ticketNo).stream()
                .filter(x -> "EMAIL".equals(x.getChannel()) && x.getRecipientId() != null
                        && !"SKIPPED".equals(x.getStatus()))
                .collect(Collectors.toList());
        assertFalse(emails.isEmpty());
        for (NotifyRecord mail : emails) {
            // 首发失败 -> RETRYING（retryCount=1，退避到点）
            assertEquals("RETRYING", mail.getStatus());
            assertEquals(1, mail.getRetryCount());
            assertNotNull(mail.getNextRetryTime());
        }

        // 第 1 轮退避到期：第 1 次重试失败 -> retryCount=2，仍 RETRYING
        expireRetryBackoff();
        sendService.retryDue();
        // 第 2 轮退避到期：第 2 次重试失败 -> 超过 maxRetry=2，置 FAILED，次数定格 2
        expireRetryBackoff();
        sendService.retryDue();

        for (NotifyRecord mail : emails) {
            NotifyRecord now = recordMapper.selectById(mail.getId());
            assertEquals("FAILED", now.getStatus(), "首发+2次重试共3次失败后应 FAILED");
            assertEquals(2, now.getRetryCount(), "重试次数口径为 2");
        }

        // 站内信依然成功；批次双通道一成一败 -> PARTIAL
        NotifyDispatch dispatch = dispatchesOf(findTicket(ticketNo).getId()).get(0);
        assertEquals("PARTIAL", dispatch.getStatus());
        assertTrue(dispatch.getSuccessChannels() >= 1);
        assertTrue(dispatch.getFailChannels() >= 1);
    }

    @Test
    @Order(2)
    void duplicateTrigger_isIdempotent() {
        String ticketNo = takeFreshSoldTicket();
        ticketService.check(ticketNo, new TicketActionRequest());
        int firstCount = recordsOf(ticketNo).size();

        // 同票再次进入触发入口（模拟事件重放）：批次唯一键拦截，不新增记录
        Ticket t = findTicket(ticketNo);
        sendService.onTicketCheck(t.getId(), "ticket_check01", "dup-req", null);
        assertEquals(firstCount, recordsOf(ticketNo).size());
        assertEquals(1, dispatchesOf(t.getId()).size());
    }

    @Test
    @Order(3)
    void recipientWithoutEmail_markedSkipped() {
        String ticketNo = takeFreshSoldTicket();
        // 新增一个只有工号、没有邮箱的场务收件人
        NotifyRecipient r = new NotifyRecipient();
        r.setRecipientName("无邮箱场务");
        r.setStaffNo("stage_no_email_" + System.nanoTime());
        r.setRoleCode("STAGE_STAFF");
        r.setEnabled(1);
        r.setStatus("ACTIVE");
        recipientMapper.insert(r);

        ticketService.check(ticketNo, new TicketActionRequest());

        // SKIPPED 邮件记录的收件地址为 null，按 recipientId 匹配本收件人的两条通道记录
        List<NotifyRecord> mine = recordsOf(ticketNo).stream()
                .filter(x -> r.getId().equals(x.getRecipientId()))
                .collect(Collectors.toList());
        NotifyRecord inboxRec = mine.stream().filter(x -> "INBOX".equals(x.getChannel())).findFirst().get();
        NotifyRecord emailRec = mine.stream().filter(x -> "EMAIL".equals(x.getChannel())).findFirst().get();
        assertEquals("SUCCESS", inboxRec.getStatus());
        assertEquals("SKIPPED", emailRec.getStatus());
        assertTrue(emailRec.getLastError().contains("无邮箱"));
    }

    // ==================== 辅助 ====================

    /** 把全部 RETRYING 记录的退避时间拨到过去，使定时重试立即可捞。 */
    private void expireRetryBackoff() {
        jdbc.update("UPDATE t_notify_record SET next_retry_time = TIMESTAMP '2000-01-01 00:00:00' "
                + "WHERE status = 'RETRYING'");
    }

    /** 顺序取一张当前 SOLD 的票（前序用例检掉的票已变 CHECKED，自动顺延到下一张）。 */
    private String takeFreshSoldTicket() {
        List<Ticket> sold = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getStatus, "SOLD")
                .orderByAsc(Ticket::getId));
        if (sold.isEmpty()) {
            throw new IllegalStateException("无可用 SOLD 票");
        }
        return sold.get(0).getTicketNo();
    }

    private Ticket findTicket(String ticketNo) {
        return ticketMapper.selectOne(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getTicketNo, ticketNo).last("LIMIT 1"));
    }

    private List<NotifyDispatch> dispatchesOf(Long ticketId) {
        return dispatchMapper.selectList(new LambdaQueryWrapper<NotifyDispatch>()
                .eq(NotifyDispatch::getTicketId, ticketId)
                .eq(NotifyDispatch::getEventCode, "TICKET_CHECK"));
    }

    private List<NotifyRecord> recordsOf(String ticketNo) {
        return recordMapper.selectList(new LambdaQueryWrapper<NotifyRecord>()
                .eq(NotifyRecord::getTicketNo, ticketNo)
                .orderByAsc(NotifyRecord::getId));
    }
}
