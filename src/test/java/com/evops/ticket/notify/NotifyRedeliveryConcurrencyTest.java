package com.evops.ticket.notify;

import com.evops.ticket.notify.channel.ChannelSendResult;
import com.evops.ticket.notify.channel.EmailChannelSender;
import com.evops.ticket.notify.entity.NotifyDispatch;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.mapper.NotifyDispatchMapper;
import com.evops.ticket.notify.mapper.NotifyRecordMapper;
import com.evops.ticket.notify.service.NotifySendService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知外发的两条并发安全红线（对应"客户收到的短信称呼/票号串成别人、重发收到两封"的事故）：
 *
 * <ol>
 *   <li><b>渲染隔离</b>：真正外呼时用的必须是数据库里那条记录自己的渲染快照（票号/称呼），
 *       不能用调用方手里可能已被串改/陈旧的内存对象——不同票、不同收件人并发投递互不串号；</li>
 *   <li><b>重发去重</b>：一条记录任一时刻只能被一个执行方（异步首投/定时续投/人工重发）投递。
 *       记录处于在途 SENDING 时，并发人工重发与定时扫描都抢不到认领、不得再发一封
 *       （邮件通道没有站内信那样的接收端唯一键，去重必须在发送侧原子保证）。</li>
 * </ol>
 *
 * 用独立合成批次/记录驱动，不依赖真实票；email-async=false 让外呼在调用线程内同步发生，
 * 用可阻塞的发送器精确制造"正在投递中"的竞态窗口。
 */
@SpringBootTest(properties = {
        "evops.notify.email-async=false",
        // 取较大的在途租约（30s，远大于 connect+read=2s 的校验下限），避免慢 CI 在竞态断言窗口内租约过期
        "evops.notify.pending-first-delivery-seconds=30",
        // 独立内存库，避免与其它 @SpringBootTest 共享上下文/遗留的 RETRYING、FAILED 记录污染计数
        "spring.datasource.url=jdbc:h2:mem:notify-redelivery;DB_CLOSE_DELAY=-1;MODE=MySQL"
})
class NotifyRedeliveryConcurrencyTest {

    @Autowired private NotifySendService sendService;
    @Autowired private NotifyRecordMapper recordMapper;
    @Autowired private NotifyDispatchMapper dispatchMapper;

    /** 可控邮件发送器：可阻塞在投递中途，并统计真正外呼次数 */
    @MockBean
    private EmailChannelSender emailChannelSender;

    @MockBean
    private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;

    @MockBean
    private com.evops.ticket.agent.transport.AgentTransportClient agentTransport;

    private final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() {
        reset(emailChannelSender);
        when(emailChannelSender.channel()).thenReturn("EMAIL");
        when(agentTransport.send(any(), any()))
                .thenReturn(new com.evops.ticket.agent.transport.SendResult(200, "OK"));
    }

    /**
     * 渲染隔离：即便调用方持有一个被串改的陈旧对象（票号/称呼已变成别人的），
     * 人工重发真正外呼时也必须以库内该记录自己的快照为准，不能把别人的票号/称呼发出去。
     */
    @Test
    void retry_usesPersistedSnapshot_notStaleCallerObject() {
        String ticketNo = "ISO-" + System.nanoTime();
        Long recordId = newFailedEmailRecord(ticketNo, "周洋", "【检票】" + ticketNo,
                "正文票号 " + ticketNo + " 观众 周洋");

        // 模拟调用链里流传的陈旧/被污染对象：票号与正文已被改成"另一个人、另一张票"
        NotifyRecord stale = recordMapper.selectById(recordId);
        stale.setTicketNo("TSH-OTHER-999");
        stale.setTitleRendered("【检票】TSH-OTHER-999");
        stale.setContentRendered("正文票号 TSH-OTHER-999 观众 李四");

        when(emailChannelSender.send(any())).thenAnswer(inv ->
                ChannelSendResult.ok(200, "MID-ISO-" + inv.<NotifyRecord>getArgument(0).getId()));

        // 走人工重发（内部以认领后库内重读的快照投递），返回值与实际外呼入参都必须是库内本人本票
        NotifyRecord done = sendService.manualRetry(recordId);
        assertEquals("SUCCESS", done.getStatus());
        assertEquals(ticketNo, done.getTicketNo());

        ArgumentCaptor<NotifyRecord> captor = ArgumentCaptor.forClass(NotifyRecord.class);
        verify(emailChannelSender, times(1)).send(captor.capture());
        NotifyRecord sent = captor.getValue();
        assertEquals(ticketNo, sent.getTicketNo(), "外呼记录必须是库内本票，不能被陈旧对象带成别人的票号");
        assertTrue(sent.getTitleRendered().contains(ticketNo), "标题必须渲染本票票号");
        assertTrue(sent.getContentRendered().contains(ticketNo), "正文必须渲染本票票号");
        assertTrue(sent.getContentRendered().contains("周洋"), "正文称呼必须是本票观众");
        assertNotEquals("TSH-OTHER-999", sent.getTicketNo());
        assertTrue(!sent.getContentRendered().contains("李四"), "不得把别人的称呼发出去");
    }

    /**
     * 重发去重：记录已被一方认领、正在外呼途中（SENDING 租约未到期）时，
     * 并发的第二次人工重发与定时续投/崩溃回收都必须原子认领失败、不得再外呼——全流程只发一封。
     */
    @Test
    void concurrentRetryWhileInFlight_sendsExactlyOnce() throws Exception {
        String ticketNo = "DEDUP-" + System.nanoTime();
        Long recordId = newFailedEmailRecord(ticketNo, "王芳", "【检票】" + ticketNo, "正文 " + ticketNo);

        CountDownLatch insideSend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger sendCalls = new AtomicInteger();
        when(emailChannelSender.send(any())).thenAnswer(inv -> {
            sendCalls.incrementAndGet();
            insideSend.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS), "测试挂起异常");
            return ChannelSendResult.ok(200, "MID-DEDUP-" + inv.<NotifyRecord>getArgument(0).getId());
        });

        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            // 线程 1：人工重发，进入外呼后阻塞在"网关返回前"，记录此刻为在途 SENDING
            Future<?> first = pool.submit(() -> sendService.manualRetry(recordId));
            assertTrue(insideSend.await(5, TimeUnit.SECONDS), "第一次重发应已进入外呼");
            assertEquals("SENDING", recordMapper.selectById(recordId).getStatus(),
                    "外呼在途必须被原子占用为 SENDING");

            // 线程 2：客服急着又点一次重发——必须被认领拒绝，而不是再发一封
            Future<?> second = pool.submit(() -> sendService.manualRetry(recordId));
            // 定时任务此刻也扫一遍：退避到点/在途崩溃都不应捞到这条租约未到期的记录
            int scheduledProcessed = sendService.retryDue();

            Exception thrown = assertThrows(Exception.class, () -> second.get(5, TimeUnit.SECONDS));
            String chain = thrown.getMessage() == null ? "" : thrown.getMessage();
            for (Throwable t = thrown.getCause(); t != null; t = t.getCause()) {
                chain += "|" + t.getMessage();
            }
            assertTrue(chain.contains("正在发送中"),
                    "在途时并发重发应得到友好的去重提示，实际：" + chain);
            assertEquals(0, scheduledProcessed, "在途租约未到期，定时任务不得重复投递");

            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        // 红线：整条竞态下来对邮件网关只外呼一次，客户只收到一封
        assertEquals(1, sendCalls.get(), "在途记录并发重发/定时扫描必须去重，只允许外呼一次");
        verify(emailChannelSender, times(1)).send(any());
        NotifyRecord done = recordMapper.selectById(recordId);
        assertEquals("SUCCESS", done.getStatus());
    }

    /** 造一条"重试耗尽 FAILED"的邮件发送记录（独立合成批次）。 */
    private Long newFailedEmailRecord(String ticketNo, String recipientName, String title, String body) {
        NotifyDispatch dispatch = new NotifyDispatch();
        dispatch.setEventCode("TICKET_CHECK");
        dispatch.setTicketId(-(800000L + seq.incrementAndGet()));
        dispatch.setTicketNo(ticketNo);
        dispatch.setStatus("FAILED");
        dispatch.setTotalChannels(1);
        dispatch.setFailChannels(1);
        dispatch.setEventTime(LocalDateTime.now());
        dispatchMapper.insert(dispatch);

        NotifyRecord record = new NotifyRecord();
        record.setDispatchId(dispatch.getId());
        record.setEventCode("TICKET_CHECK");
        record.setChannel("EMAIL");
        record.setTemplateId(1L);
        record.setTemplateVersion(1);
        record.setRecipientName(recipientName);
        record.setRecipientAddress("dedup" + seq.get() + "@example.cn");
        record.setTicketNo(ticketNo);
        record.setTitleRendered(title);
        record.setContentRendered(body);
        record.setVarsSnapshot("{\"ticketNo\":\"" + ticketNo + "\"}");
        record.setStatus("FAILED");
        record.setRetryCount(2);
        record.setMaxRetry(2);
        record.setOperator("ticket_check01");
        record.setBizTime(LocalDateTime.now());
        record.setCreateTime(LocalDateTime.now());
        recordMapper.insert(record);
        return record.getId();
    }
}
