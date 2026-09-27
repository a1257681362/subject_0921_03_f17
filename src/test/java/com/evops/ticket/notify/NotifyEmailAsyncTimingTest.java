package com.evops.ticket.notify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.ticket.TicketActionRequest;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.mapper.TicketMapper;
import com.evops.ticket.notify.channel.ChannelSendResult;
import com.evops.ticket.notify.channel.EmailChannelSender;
import com.evops.ticket.notify.entity.NotifyDispatch;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.mapper.NotifyDispatchMapper;
import com.evops.ticket.notify.mapper.NotifyRecordMapper;
import com.evops.ticket.notify.service.NotifySendService;
import com.evops.ticket.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 邮件外呼异步时效验证（email-async=true）：
 * 1) 检票接口不等慢邮件网关：check 返回时站内信已即时 SUCCESS，邮件仍在外呼途中（未 SUCCESS）；
 * 2) 慢外呼结束后邮件自动收敛 SUCCESS，不丢通知；
 * 3) PENDING 超时未首投（模拟线程池拒绝/线程异常）由 retryDue 首投兜底补发；
 * 4) 同票重复触发仍幂等。
 *
 * 同步外呼行为（email-async=false）由 {@link CheckinNotifyIntegrationTest} 覆盖。
 */
@SpringBootTest(properties = {
        "evops.notify.email-async=true",
        "evops.notify.email-pool-size=2",
        "evops.notify.pending-first-delivery-seconds=2"
})
class NotifyEmailAsyncTimingTest {

    @Autowired private TicketService ticketService;
    @Autowired private TicketMapper ticketMapper;
    @Autowired private NotifySendService sendService;
    @Autowired private NotifyRecordMapper recordMapper;
    @Autowired private NotifyDispatchMapper dispatchMapper;
    @Autowired private com.evops.ticket.notify.config.NotifyProperties properties;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 桩掉代理平台出站，避免检票触发平台推送噪音 */
    @MockBean
    private com.evops.ticket.agent.transport.AgentTransportClient agentTransport;

    /** 桩掉每分钟 cron，用例内手动调用 sendService.retryDue() 精确控制兜底时机 */
    @MockBean
    private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;

    /** 可控慢邮件网关：release 前所有邮件外呼都阻塞，release 后成功。每用例前重置以保证测试隔离。 */
    @MockBean
    private EmailChannelSender emailChannelSender;

    private CountDownLatch gate;

    @BeforeEach
    void setUp() {
        gate = new CountDownLatch(1);
        RequestContext.set(new RequestContext.Context("ticket_check01",
                "notify-async-" + System.nanoTime(), "/test", 0L));
        when(agentTransport.send(any(), any()))
                .thenReturn(new com.evops.ticket.agent.transport.SendResult(200, "OK"));
        org.mockito.Mockito.reset(emailChannelSender);
        when(emailChannelSender.channel()).thenReturn("EMAIL");
        when(emailChannelSender.send(any())).thenAnswer(inv -> {
            gate.await(10, TimeUnit.SECONDS); // 模拟邮件网关慢/抖动
            NotifyRecord r = inv.getArgument(0);
            return ChannelSendResult.ok(200, "MID-ASYNC-" + r.getId());
        });
    }

    @Test
    void checkReturnsBeforeSlowEmail_inboxImmediate_emailCompletesAfter() throws Exception {
        assertTrue(properties.isEmailAsync(), "本用例必须开启邮件异步");
        String ticketNo = takeFreshSoldTicket();

        ticketService.check(ticketNo, new TicketActionRequest()); // 慢邮件网关下必须快速返回

        List<NotifyRecord> records = recordsOf(ticketNo);
        List<NotifyRecord> inbox = channel(records, "INBOX");
        List<NotifyRecord> emails = channel(records, "EMAIL");
        assertFalse(emails.isEmpty());

        // 检票返回时：本地站内信已即时送达；慢邮件仍在外呼途中（PENDING 排队或已认领 RETRYING），绝无 SUCCESS
        assertTrue(inbox.stream().allMatch(r -> "SUCCESS".equals(r.getStatus())),
                "站内信应在检票线程内即时成功，实际：" + statuses(inbox));
        assertTrue(emails.stream().noneMatch(r -> "SUCCESS".equals(r.getStatus())),
                "检票返回时慢邮件不得已完成（接口被网关拖住），实际：" + statuses(emails));
        assertTrue(emails.stream().allMatch(r ->
                "PENDING".equals(r.getStatus()) || "RETRYING".equals(r.getStatus())));

        // 放开慢网关，异步外呼收敛为 SUCCESS（最多等 5s）
        gate.countDown();
        assertTrue(awaitStatus(emails, "SUCCESS", 5),
                "慢网关恢复后邮件应收敛成功，实际：" + statuses(recordMapper.selectList(
                        new LambdaQueryWrapper<NotifyRecord>().eq(NotifyRecord::getTicketNo, ticketNo))));
    }

    @Test
    void pendingEmailPastDeadline_isDeliveredByScheduledFallback() {
        assertTrue(properties.isEmailAsync());
        gate.countDown(); // 本用例不模拟慢网关：兜底外呼应立即成功

        // 直接造一条"线程池拒绝/线程异常导致从未外呼"的 PENDING 邮件（独立批次，不占用真实票、
        // 不依赖异步时序），创建时间回拨到首投兜底时限之前
        NotifyDispatch dispatch = new NotifyDispatch();
        dispatch.setEventCode("TICKET_CHECK");
        dispatch.setTicketId(-900001L);
        dispatch.setTicketNo("FB-STUCK-1");
        dispatch.setStatus("PENDING");
        dispatch.setTotalChannels(1);
        dispatch.setEventTime(LocalDateTime.now());
        dispatchMapper.insert(dispatch);

        NotifyRecord stuck = new NotifyRecord();
        stuck.setDispatchId(dispatch.getId());
        stuck.setEventCode("TICKET_CHECK");
        stuck.setChannel("EMAIL");
        stuck.setTemplateId(1L);
        stuck.setTemplateVersion(1);
        stuck.setRecipientName("兜底场务");
        stuck.setRecipientAddress("fallback@example.cn");
        stuck.setTicketNo("FB-STUCK-1");
        stuck.setTitleRendered("兜底标题");
        stuck.setContentRendered("兜底正文");
        stuck.setStatus("PENDING");
        stuck.setRetryCount(0);
        stuck.setMaxRetry(2);
        stuck.setOperator("ticket_check01");
        stuck.setBizTime(LocalDateTime.now());
        stuck.setCreateTime(LocalDateTime.now());
        recordMapper.insert(stuck);
        // 回拨创建时间到首投兜底时限（2s）之前，模拟记录落库后始终没被外呼线程处理
        jdbc.update("UPDATE t_notify_record SET create_time = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(30), stuck.getId());

        sendService.retryDue(); // 每分钟定时任务：原子认领 PENDING 并完成首投

        NotifyRecord done = recordMapper.selectById(stuck.getId());
        assertEquals("SUCCESS", done.getStatus(),
                "PENDING 超时未首投的邮件应由定时兜底补发成功，实际：" + done.getStatus());
    }

    // ---------------- helpers ----------------

    private String takeFreshSoldTicket() {
        List<Ticket> sold = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getStatus, "SOLD").orderByAsc(Ticket::getId));
        if (sold.isEmpty()) {
            throw new IllegalStateException("无可用 SOLD 票");
        }
        return sold.get(0).getTicketNo();
    }

    private List<NotifyRecord> recordsOf(String ticketNo) {
        return recordMapper.selectList(new LambdaQueryWrapper<NotifyRecord>()
                .eq(NotifyRecord::getTicketNo, ticketNo).orderByAsc(NotifyRecord::getId));
    }

    private List<NotifyRecord> channel(List<NotifyRecord> all, String channel) {
        java.util.List<NotifyRecord> list = new java.util.ArrayList<>();
        for (NotifyRecord r : all) {
            if (channel.equals(r.getChannel()) && r.getRecipientId() != null
                    && !"SKIPPED".equals(r.getStatus())) {
                list.add(r);
            }
        }
        return list;
    }

    private List<String> statuses(List<NotifyRecord> rs) {
        java.util.List<String> s = new java.util.ArrayList<>();
        for (NotifyRecord r : rs) {
            s.add(r.getId() + ":" + r.getStatus());
        }
        return s;
    }

    private boolean awaitStatus(List<NotifyRecord> rs, String expected, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (NotifyRecord r : rs) {
                NotifyRecord fresh = recordMapper.selectById(r.getId());
                if (fresh == null || !expected.equals(fresh.getStatus())) {
                    all = false;
                }
            }
            if (all) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
