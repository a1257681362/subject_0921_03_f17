package com.evops.ticket.notify;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.web.RequestContext;
import com.evops.ticket.agent.entity.AgentMessage;
import com.evops.ticket.agent.enums.MessageDirection;
import com.evops.ticket.agent.enums.MessageStatus;
import com.evops.ticket.agent.mapper.AgentMessageMapper;
import com.evops.ticket.agent.service.OutboundAgentService;
import com.evops.ticket.agent.transport.AgentTransportClient;
import com.evops.ticket.agent.transport.SendResult;
import com.evops.ticket.agent.transport.TransportException;
import com.evops.ticket.dto.order.OrderDetailVO;
import com.evops.ticket.dto.order.SaleCreateRequest;
import com.evops.ticket.dto.order.SaleItem;
import com.evops.ticket.dto.ticket.TicketActionRequest;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.ShowSessionMapper;
import com.evops.ticket.notify.channel.ChannelSendResult;
import com.evops.ticket.notify.channel.EmailChannelSender;
import com.evops.ticket.notify.channel.InboxChannelSender;
import com.evops.ticket.notify.entity.InboxMessage;
import com.evops.ticket.notify.entity.NotifyDispatch;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.mapper.InboxMessageMapper;
import com.evops.ticket.notify.mapper.NotifyDispatchMapper;
import com.evops.ticket.notify.mapper.NotifyRecordMapper;
import com.evops.ticket.notify.service.NotifySendService;
import com.evops.ticket.service.SaleOrderService;
import com.evops.ticket.service.TicketService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * 手工台账批量补录场景的两道防线回归（email-async=true，与现场口径一致）：
 *
 * <p><b>渲染隔离</b>：批量检票（补录台账）时，每张票的通知按「票 → 变量表 → 逐记录快照」
 * 独立渲染——任何一条记录的标题/正文/变量快照只含本票数据，不串另一张票的票号/剧目/座位；
 * 收件人称呼（recipientName）与投递地址（工号/邮箱）一一对应，不出现"周洋的信写王芳的名字"。
 *
 * <p><b>重发去重</b>：手动重发、事件重放、通道重投均不产生重复投递——
 * 已成功记录拒绝重发；同票同事件重放仍单批次；站内信按 record_id 唯一不重复落信；
 * 出站报文重发沿用原 messageId（平台幂等键），不新增归档行。
 */
@SpringBootTest(properties = {
        "evops.notify.email-async=true",
        "evops.notify.email-pool-size=4",
        // 必须严格大于单次外呼超时上限（测试环境 connect+read=2000ms），取最小合法值
        "evops.notify.pending-first-delivery-seconds=3"
})
class NotifyRenderIsolationDedupTest {

    @Autowired private TicketService ticketService;
    @Autowired private SaleOrderService orderService;
    @Autowired private ShowSessionMapper sessionMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private NotifySendService sendService;
    @Autowired private NotifyRecordMapper recordMapper;
    @Autowired private NotifyDispatchMapper dispatchMapper;
    @Autowired private InboxMessageMapper inboxMapper;
    @Autowired private InboxChannelSender inboxChannelSender;
    @Autowired private OutboundAgentService outboundAgentService;
    @Autowired private AgentMessageMapper agentMessageMapper;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    /** 桩掉平台出站传输，默认成功；用例按需改为失败 */
    @MockBean private AgentTransportClient agentTransport;
    /** 桩掉每分钟 cron，重试时机由用例手动控制 */
    @MockBean private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;
    @MockBean private com.evops.ticket.agent.service.AgentMessageRetryJob agentRetryJob;
    /** 可控邮件通道：默认即时成功并留痕每次投递，便于按票号核对"发给谁、发的什么" */
    @MockBean private EmailChannelSender emailChannelSender;

    /** 邮件投递留痕：ticketNo -> 该票每一次邮件外呼的快照（按到达顺序） */
    private final Map<String, List<NotifyRecord>> emailCalls = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Context("ticket_check01",
                "render-iso-" + System.nanoTime(), "/test", 0L));
        emailCalls.clear();
        org.mockito.Mockito.reset(agentTransport, emailChannelSender);
        // doReturn/doAnswer 打桩：when(mock.m()) 语法会先真实调用一次 mock，
        // 旧桩（抛异常/读参数）会在打桩当场误触发
        org.mockito.Mockito.doReturn(new SendResult(200, "OK"))
                .when(agentTransport).send(anyString(), anyString());
        org.mockito.Mockito.doReturn("EMAIL").when(emailChannelSender).channel();
        stubEmailSuccess();
    }

    private void stubEmailSuccess() {
        org.mockito.Mockito.doAnswer(inv -> {
            NotifyRecord r = inv.getArgument(0);
            emailCalls.computeIfAbsent(r.getTicketNo(), k -> new CopyOnWriteArrayList<>()).add(r);
            return ChannelSendResult.ok(200, "MID-ISO-" + r.getId());
        }).when(emailChannelSender).send(any());
    }

    private void stubEmailFailure() {
        org.mockito.Mockito.doAnswer(inv -> {
            NotifyRecord r = inv.getArgument(0);
            emailCalls.computeIfAbsent(r.getTicketNo(), k -> new CopyOnWriteArrayList<>()).add(r);
            return ChannelSendResult.fail(500, "模拟邮件网关故障");
        }).when(emailChannelSender).send(any());
    }

    // ==================== 渲染隔离 ====================

    /**
     * 批量补录两张不同剧目/座位的票：通知内容逐票隔离——
     * 每条记录的渲染标题/正文/变量快照只含本票数据；收件人称呼与投递地址对应同一人。
     */
    @Test
    void batchCheckin_renderIsolation_noCrossTicket_noCrossRecipient() throws Exception {
        String ticketA = sellOne("RIA").ticketNo;
        String ticketB = sellOne("RIB").ticketNo;

        // 手工台账补录节奏：两张票连续检票（异步邮件在后台并发外呼）
        ticketService.check(ticketA, new TicketActionRequest());
        ticketService.check(ticketB, new TicketActionRequest());

        awaitEmailSuccess(ticketA, 10);
        awaitEmailSuccess(ticketB, 10);

        // ① 票 A 的全部通知：只含 A 的票号/剧目/座位，绝不含 B 的；B 同理
        assertRenderIsolated(ticketA, "测试剧目RIA", ticketB, "测试剧目RIB");
        assertRenderIsolated(ticketB, "测试剧目RIB", ticketA, "测试剧目RIA");

        // ② 称呼隔离：每条记录的收件人名字与投递地址必须属于同一个人
        for (NotifyRecord r : recordsOf(ticketA)) {
            assertRecipientConsistent(r);
        }
        for (NotifyRecord r : recordsOf(ticketB)) {
            assertRecipientConsistent(r);
        }

        // ③ 站内信逐条对应：每封信的称呼/工号与内容票号不串
        for (String ticketNo : new String[]{ticketA, ticketB}) {
            List<InboxMessage> mails = inboxOf(ticketNo);
            assertFalse(mails.isEmpty());
            for (InboxMessage m : mails) {
                assertTrue(m.getContent().contains(ticketNo),
                        "站内信正文票号应为本票 " + ticketNo + "，实际：" + m.getContent());
                assertRecipientNameMatchesStaffNo(m.getStaffNo(), m.getRecipientName());
            }
        }

        // ④ 邮件通道留痕：每一次外呼的载荷票号与正文票号一致（异步并发下不串票）
        for (Map.Entry<String, List<NotifyRecord>> e : emailCalls.entrySet()) {
            for (NotifyRecord call : e.getValue()) {
                assertEquals(e.getKey(), call.getTicketNo());
                assertTrue(call.getContentRendered().contains(e.getKey()),
                        "邮件正文票号应与载荷票号一致 " + e.getKey());
            }
        }

        // ⑤ 平台出站报文同样逐票隔离：A 的出站原文只含 A 的票号（平台侧短信不会拿错票号）
        assertOutboundIsolated(ticketA, ticketB);
        assertOutboundIsolated(ticketB, ticketA);
    }

    // ==================== 重发去重 ====================

    /** 已成功记录拒绝重发；站内信通道按 record_id 幂等，重复投递不产生第二封信。 */
    @Test
    void successfulRecord_manualRetryRejected_inboxResendDeduplicated() {
        String ticketNo = sellOne("RID").ticketNo;
        ticketService.check(ticketNo, new TicketActionRequest());
        awaitEmailSuccess(ticketNo, 10);

        List<NotifyRecord> records = recordsOf(ticketNo);
        assertFalse(records.isEmpty());
        long inboxBefore = inboxOf(ticketNo).size();

        // 已成功记录（站内信/邮件各抽一条）手动重发：一律拒绝，不产生任何新投递
        NotifyRecord inboxRec = records.stream().filter(r -> "INBOX".equals(r.getChannel()))
                .findFirst().orElseThrow(AssertionError::new);
        NotifyRecord emailRec = records.stream().filter(r -> "EMAIL".equals(r.getChannel()))
                .filter(r -> !"SKIPPED".equals(r.getStatus())).findFirst().orElseThrow(AssertionError::new);
        assertThrows(IllegalArgumentException.class, () -> sendService.manualRetry(inboxRec.getId()));
        assertThrows(IllegalArgumentException.class, () -> sendService.manualRetry(emailRec.getId()));

        // 站内信通道对同一记录重复投递（模拟重试/并发重复到达）：仍只落一封信
        inboxChannelSender.send(recordMapper.selectById(inboxRec.getId()));
        inboxChannelSender.send(recordMapper.selectById(inboxRec.getId()));
        assertEquals(inboxBefore, inboxOf(ticketNo).size(), "站内信按 record_id 去重，重投不生第二封");

        // 邮件外呼次数不因拒绝重发而增加（每条 EMAIL 记录恰好投递 1 次）
        long emailRecords = records.stream().filter(r -> "EMAIL".equals(r.getChannel())
                && !"SKIPPED".equals(r.getStatus())).count();
        assertEquals(emailRecords, emailCalls.getOrDefault(ticketNo, Collections.emptyList()).size(),
                "成功的邮件记录不应被重复外呼");
    }

    /** 失败重试耗尽后手动重发：恢复成功只更新原记录，不新增记录、不重复落站内信。 */
    @Test
    void failedRecord_manualRetry_recoversInPlace_noDuplicateRows() {
        stubEmailFailure();
        String ticketNo = sellOne("RIF").ticketNo;
        ticketService.check(ticketNo, new TicketActionRequest());

        // 等异步首投失败落 RETRYING（attempt=1）
        awaitRecordState(ticketNo, "EMAIL", "RETRYING", 1, 10);
        // 第 2、3 次投递均失败：重试 2 次耗尽置 FAILED
        expireRetryBackoff();
        sendService.retryDue();
        awaitRecordState(ticketNo, "EMAIL", "RETRYING", 2, 10);
        expireRetryBackoff();
        sendService.retryDue();
        awaitRecordState(ticketNo, "EMAIL", "FAILED", 2, 10);

        int recordsBefore = recordsOf(ticketNo).size();
        long inboxBefore = inboxOf(ticketNo).size();

        // 网关恢复，手动重发：原记录就地转 SUCCESS
        stubEmailSuccess();
        List<NotifyRecord> failed = recordsOf(ticketNo).stream()
                .filter(r -> "FAILED".equals(r.getStatus())).collect(Collectors.toList());
        assertFalse(failed.isEmpty());
        for (NotifyRecord r : failed) {
            NotifyRecord after = sendService.manualRetry(r.getId());
            assertEquals("SUCCESS", after.getStatus());
            assertEquals(r.getId(), after.getId(), "重发必须就地更新原记录，不得另起新行");
        }

        // 重发后再重发：已成功，拒绝
        NotifyRecord any = recordsOf(ticketNo).stream().filter(r -> "EMAIL".equals(r.getChannel())
                && !"SKIPPED".equals(r.getStatus())).findFirst().orElseThrow(AssertionError::new);
        assertThrows(IllegalArgumentException.class, () -> sendService.manualRetry(any.getId()));

        // 去重口径：发送记录不增、站内信不增、批次仍 1 个
        assertEquals(recordsBefore, recordsOf(ticketNo).size(), "重发不得新增发送记录行");
        assertEquals(inboxBefore, inboxOf(ticketNo).size(), "重发不得重复落站内信");
        assertEquals(1, dispatchesOf(ticketNo).size(), "同票同事件始终单批次");
    }

    /** 事件重放（补发/重复消费）：同票同事件只产生一个批次、一套记录。 */
    @Test
    void duplicateEventTrigger_singleDispatch_singleRecordSet() {
        String ticketNo = sellOne("RIP").ticketNo;
        ticketService.check(ticketNo, new TicketActionRequest());
        awaitEmailSuccess(ticketNo, 10);
        int recordsBefore = recordsOf(ticketNo).size();
        long inboxBefore = inboxOf(ticketNo).size();
        int emailCallsBefore = emailCalls.getOrDefault(ticketNo, Collections.emptyList()).size();

        // 同一票同一事件再次进入触发入口（消息重放/人工补发）
        Long ticketId = ticketIdOf(ticketNo);
        sendService.onTicketCheck(ticketId, "ticket_check01", "replay-1", null);
        sendService.onTicketCheck(ticketId, "ticket_check01", "replay-2", null);

        assertEquals(1, dispatchesOf(ticketNo).size(), "事件重放不得产生第二个批次");
        assertEquals(recordsBefore, recordsOf(ticketNo).size(), "事件重放不得新增发送记录");
        assertEquals(inboxBefore, inboxOf(ticketNo).size(), "事件重放不得重复落站内信");
        assertEquals(emailCallsBefore, emailCalls.getOrDefault(ticketNo, Collections.emptyList()).size(),
                "事件重放不得重复外呼邮件");
    }

    /** 出站报文重发：沿用原 messageId（平台幂等键）就地更新，不新增归档行。 */
    @Test
    void agentOutboundRetry_sameMessageId_noNewArchiveRow() {
        // 平台不可达：首发失败落 RETRYING
        org.mockito.Mockito.doThrow(new TransportException("connection refused", new RuntimeException()))
                .when(agentTransport).send(anyString(), anyString());
        String ticketNo = sellOne("RIA2").ticketNo;

        List<AgentMessage> before = outboundOf(ticketNo);
        assertEquals(1, before.size(), "出票只归档一条出站报文");
        assertEquals(MessageStatus.RETRYING.name(), before.get(0).getStatus());
        String messageId = before.get(0).getMessageId();

        // 平台恢复后手动重发：同一归档行就地转 SUCCESS，messageId 不变（平台按它幂等去重）
        org.mockito.Mockito.doReturn(new SendResult(200, "OK")).when(agentTransport).send(anyString(), anyString());
        AgentMessage retried = outboundAgentService.manualRetry(before.get(0).getId());
        assertEquals(MessageStatus.SUCCESS.name(), retried.getStatus());
        assertEquals(messageId, retried.getMessageId(), "重发必须沿用原 messageId，平台侧才能幂等去重");

        List<AgentMessage> after = outboundOf(ticketNo);
        assertEquals(1, after.size(), "重发不得新增出站归档行");
        // 已成功报文拒绝再次重发
        assertThrows(IllegalArgumentException.class,
                () -> outboundAgentService.manualRetry(after.get(0).getId()));
    }

    // ==================== 断言辅助 ====================

    /** 该票全部通知记录：渲染内容只含本票数据，不含另一张票的任何痕迹。 */
    private void assertRenderIsolated(String ticketNo, String showName,
                                      String otherTicketNo, String otherShowName) throws Exception {
        List<NotifyRecord> records = recordsOf(ticketNo);
        assertFalse(records.isEmpty());
        for (NotifyRecord r : records) {
            assertEquals(ticketNo, r.getTicketNo(), "记录票号列应为本票");
            assertTrue(r.getTitleRendered().contains(ticketNo),
                    "标题应含本票票号 " + ticketNo + "，实际：" + r.getTitleRendered());
            assertTrue(r.getContentRendered().contains(ticketNo),
                    "正文应含本票票号 " + ticketNo + "，实际：" + r.getContentRendered());
            assertTrue(r.getContentRendered().contains(showName),
                    "正文应含本票剧目 " + showName + "，实际：" + r.getContentRendered());
            assertFalse(r.getTitleRendered().contains(otherTicketNo),
                    "标题串票！含另一张票 " + otherTicketNo + "：" + r.getTitleRendered());
            assertFalse(r.getContentRendered().contains(otherTicketNo),
                    "正文串票！含另一张票 " + otherTicketNo + "：" + r.getContentRendered());
            assertFalse(r.getContentRendered().contains(otherShowName),
                    "正文串剧目！含另一张票剧目 " + otherShowName + "：" + r.getContentRendered());
            // 变量快照与渲染同源：快照里的票号就是本票
            JsonNode vars = objectMapper.readTree(r.getVarsSnapshot());
            assertEquals(ticketNo, vars.get("ticketNo").asText(), "变量快照票号应为本票");
        }
    }

    /** 收件人称呼与投递地址必须属于同一个人（种子口径：周洋↔stage01↔zhouyang.*；王芳↔venue_admin↔wangfang.*）。 */
    private void assertRecipientConsistent(NotifyRecord r) {
        String address = r.getRecipientAddress();
        if (address == null) {
            // 无地址记录（SKIPPED）：称呼仍须与收件人档案一致，本用例不展开
            return;
        }
        assertRecipientNameMatchesStaffNo(address, r.getRecipientName());
    }

    private void assertRecipientNameMatchesStaffNo(String addressOrStaffNo, String recipientName) {
        if ("stage01".equals(addressOrStaffNo) || "zhouyang.stage@example.cn".equals(addressOrStaffNo)) {
            assertEquals("周洋", recipientName, "stage01 的称呼应是周洋，实际：" + recipientName);
        } else if ("venue_admin".equals(addressOrStaffNo) || "wangfang.manager@example.cn".equals(addressOrStaffNo)) {
            assertEquals("王芳", recipientName, "venue_admin 的称呼应是王芳，实际：" + recipientName);
        }
        // 其他收件人（如测试追加的无邮箱场务）不在种子口径内，不约束
    }

    /** 该票出站报文原文只含本票票号，不含另一张票票号。 */
    private void assertOutboundIsolated(String ticketNo, String otherTicketNo) {
        List<AgentMessage> msgs = outboundOf(ticketNo);
        assertFalse(msgs.isEmpty());
        for (AgentMessage m : msgs) {
            assertEquals(ticketNo, m.getTicketNo(), "出站归档票号列应为本票");
            assertTrue(m.getPayload().contains("<ticketNo>" + ticketNo + "</ticketNo>"),
                    "出站原文应含本票票号 " + ticketNo);
            assertFalse(m.getPayload().contains(otherTicketNo),
                    "出站原文串票！含另一张票 " + otherTicketNo);
        }
    }

    // ==================== 数据辅助 ====================

    private static class Fixture {
        final String ticketNo;
        Fixture(String ticketNo) { this.ticketNo = ticketNo; }
    }

    /** 独立剧目/座位/售票建档：票号、剧目名、座位号均按 sessionCode 区分，互不重叠。 */
    private Fixture sellOne(String sessionCode) {
        ShowSession s = new ShowSession();
        s.setShowName("测试剧目" + sessionCode);
        s.setSessionCode(sessionCode);
        s.setVenue("隔离测试场馆");
        s.setShowTime(LocalDateTime.now().plusDays(7));
        s.setStatus("OPEN");
        s.setOperator("tester");
        sessionMapper.insert(s);

        Seat seat = new Seat();
        seat.setVenue("隔离测试场馆");
        seat.setArea("隔离区");
        seat.setRowNo("R");
        seat.setSeatNo(sessionCode);
        seat.setOperator("tester");
        seatMapper.insert(seat);

        SaleCreateRequest req = new SaleCreateRequest();
        req.setSessionId(s.getId());
        SaleItem item = new SaleItem();
        item.setSeatId(seat.getId());
        item.setPrice(new BigDecimal("180.00"));
        req.setItems(Collections.singletonList(item));

        OrderDetailVO order = orderService.create(req);
        return new Fixture(order.getTickets().get(0).getTicketNo());
    }

    private Long ticketIdOf(String ticketNo) {
        return jdbc.queryForObject("SELECT id FROM t_ticket WHERE ticket_no = ?", Long.class, ticketNo);
    }

    private List<NotifyRecord> recordsOf(String ticketNo) {
        return recordMapper.selectList(new LambdaQueryWrapper<NotifyRecord>()
                .eq(NotifyRecord::getTicketNo, ticketNo).orderByAsc(NotifyRecord::getId));
    }

    private List<NotifyDispatch> dispatchesOf(String ticketNo) {
        return dispatchMapper.selectList(new LambdaQueryWrapper<NotifyDispatch>()
                .eq(NotifyDispatch::getTicketNo, ticketNo)
                .eq(NotifyDispatch::getEventCode, "TICKET_CHECK"));
    }

    private List<InboxMessage> inboxOf(String ticketNo) {
        return inboxMapper.selectList(new LambdaQueryWrapper<InboxMessage>()
                .eq(InboxMessage::getBizKey, ticketNo).orderByAsc(InboxMessage::getId));
    }

    private List<AgentMessage> outboundOf(String ticketNo) {
        return agentMessageMapper.selectList(new LambdaQueryWrapper<AgentMessage>()
                .eq(AgentMessage::getTicketNo, ticketNo)
                .eq(AgentMessage::getDirection, MessageDirection.OUTBOUND.name())
                .orderByAsc(AgentMessage::getId));
    }

    /** 等该票全部非 SKIPPED 邮件记录收敛 SUCCESS（异步外呼）。 */
    private void awaitEmailSuccess(String ticketNo, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<NotifyRecord> emails = recordsOf(ticketNo).stream()
                    .filter(r -> "EMAIL".equals(r.getChannel()) && !"SKIPPED".equals(r.getStatus()))
                    .collect(Collectors.toList());
            if (!emails.isEmpty() && emails.stream().allMatch(r -> "SUCCESS".equals(r.getStatus()))) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("邮件未在 " + timeoutSeconds + "s 内收敛 SUCCESS：" + recordsOf(ticketNo));
    }

    /** 等该票指定通道记录收敛到目标状态与重试次数。 */
    private void awaitRecordState(String ticketNo, String channel, String status, int retryCount,
                                  int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            List<NotifyRecord> rs = recordsOf(ticketNo).stream()
                    .filter(r -> channel.equals(r.getChannel()) && !"SKIPPED".equals(r.getStatus()))
                    .collect(Collectors.toList());
            if (!rs.isEmpty() && rs.stream().allMatch(r -> status.equals(r.getStatus())
                    && r.getRetryCount() != null && r.getRetryCount() == retryCount)) {
                return;
            }
            sleep(100);
        }
        throw new AssertionError("记录未在 " + timeoutSeconds + "s 内收敛到 " + status + "/retry=" + retryCount
                + "：" + recordsOf(ticketNo));
    }

    /** 把全部 RETRYING 记录的退避时间拨到过去，使定时重试立即可捞。 */
    private void expireRetryBackoff() {
        jdbc.update("UPDATE t_notify_record SET next_retry_time = TIMESTAMP '2000-01-01 00:00:00' "
                + "WHERE status = 'RETRYING'");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
