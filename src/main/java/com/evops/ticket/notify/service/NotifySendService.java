package com.evops.ticket.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.ticket.entity.SaleOrder;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.mapper.SaleOrderMapper;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.mapper.ShowSessionMapper;
import com.evops.ticket.mapper.TicketMapper;
import com.evops.ticket.notify.channel.ChannelSendResult;
import com.evops.ticket.notify.channel.EmailDispatchExecutor;
import com.evops.ticket.notify.channel.NotifyChannelSender;
import com.evops.ticket.notify.config.NotifyProperties;
import com.evops.ticket.notify.entity.NotifyDispatch;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.entity.NotifyRecipient;
import com.evops.ticket.notify.entity.NotifyTemplate;
import com.evops.ticket.notify.enums.DispatchStatus;
import com.evops.ticket.notify.enums.NotifyChannel;
import com.evops.ticket.notify.enums.NotifyEventCode;
import com.evops.ticket.notify.enums.RecordStatus;
import com.evops.ticket.notify.mapper.NotifyRecipientMapper;
import com.evops.ticket.notify.mapper.NotifyTemplateMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检票通知发送编排。
 *
 * <p>流程：检票事务提交后触发 -> 二次加工票/订单/场次/座位为模板变量 ->
 * 取启用模板与场务收件人 -> 逐「通道 × 收件人」渲染并快照 -> 建批次与发送记录 ->
 * 站内信/邮件双通道立即投递；失败退避重试 2 次（定时任务捞 RETRYING 续投），耗尽置 FAILED。
 *
 * <p>批次按 (eventCode, ticketId) 唯一，事件重复投递不会重复通知。
 * 渲染标题/正文/变量集逐记录快照：模板后续改版只升 tpl_version，已发内容原样不变。
 */
@Service
public class NotifySendService {

    private static final Logger log = LoggerFactory.getLogger(NotifySendService.class);

    private final NotifyProperties properties;
    private final NotifyStore store;
    private final NotifyTemplateMapper templateMapper;
    private final NotifyRecipientMapper recipientMapper;
    private final TicketMapper ticketMapper;
    private final SaleOrderMapper orderMapper;
    private final ShowSessionMapper sessionMapper;
    private final SeatMapper seatMapper;
    private final ObjectMapper objectMapper;

    /** channel -> sender。不在构造期固化（发送器可能被测试替身替换），每次按 channel() 现取。 */
    private final List<NotifyChannelSender> senderList;

    /** 邮件外呼线程池（检票线程不等邮件网关）；同步模式下不使用 */
    private final EmailDispatchExecutor emailExecutor;

    public NotifySendService(NotifyProperties properties, NotifyStore store,
                             NotifyTemplateMapper templateMapper, NotifyRecipientMapper recipientMapper,
                             TicketMapper ticketMapper, SaleOrderMapper orderMapper,
                             ShowSessionMapper sessionMapper, SeatMapper seatMapper,
                             ObjectMapper objectMapper, List<NotifyChannelSender> senderList,
                             EmailDispatchExecutor emailExecutor) {
        this.properties = properties;
        this.store = store;
        this.templateMapper = templateMapper;
        this.recipientMapper = recipientMapper;
        this.ticketMapper = ticketMapper;
        this.orderMapper = orderMapper;
        this.sessionMapper = sessionMapper;
        this.seatMapper = seatMapper;
        this.objectMapper = objectMapper;
        this.senderList = senderList;
        this.emailExecutor = emailExecutor;
    }

    /** 检票事件入口：幂等（同票同事件只通知一次）。任何异常不外溢影响主流程。 */
    public void onTicketCheck(Long ticketId, String operator, String requestId, String reason) {
        if (!properties.isEnabled()) {
            log.debug("检票通知总开关关闭，跳过 ticketId={}", ticketId);
            return;
        }
        try {
            trigger(ticketId, operator, requestId, reason);
        } catch (Exception e) {
            // 通知失败绝不反噬票务主流程（主事务此时已提交）；落库/投递失败均有记录与重试兜底
            log.error("检票通知触发失败 ticketId={}", ticketId, e);
        }
    }

    private void trigger(Long ticketId, String operator, String requestId, String reason) {
        Ticket ticket = ticketMapper.selectById(ticketId);
        if (ticket == null) {
            log.warn("检票通知找不到票，跳过 ticketId={}", ticketId);
            return;
        }
        SaleOrder order = orderMapper.selectById(ticket.getOrderId());
        ShowSession session = sessionMapper.selectById(ticket.getSessionId());
        Seat seat = seatMapper.selectById(ticket.getSeatId());
        LocalDateTime eventTime = ticket.getBizTime() != null ? ticket.getBizTime() : LocalDateTime.now();
        Map<String, String> vars = NotifyVarsBuilder.build(ticket, order, session, seat, operator, reason, eventTime);

        List<NotifyTemplate> templates = activeTemplates(NotifyEventCode.TICKET_CHECK.code());
        List<NotifyRecipient> recipients = activeRecipients();
        if (templates.isEmpty()) {
            log.warn("检票通知未配置启用模板，事件 {} 无通道可发 ticketNo={}",
                    NotifyEventCode.TICKET_CHECK.code(), ticket.getTicketNo());
            return;
        }
        if (recipients.isEmpty()) {
            log.warn("检票通知无启用场务收件人，ticketNo={}", ticket.getTicketNo());
            return;
        }

        NotifyDispatch dispatch = new NotifyDispatch();
        dispatch.setEventCode(NotifyEventCode.TICKET_CHECK.code());
        dispatch.setTicketId(ticketId);
        dispatch.setTicketNo(ticket.getTicketNo());
        dispatch.setOrderNo(order == null ? null : order.getOrderNo());
        dispatch.setSessionCode(session == null ? null : session.getSessionCode());
        dispatch.setStatus(RecordStatus.PENDING.name());
        dispatch.setTotalChannels(0);
        dispatch.setSuccessChannels(0);
        dispatch.setFailChannels(0);
        dispatch.setTriggerOperator(operator);
        dispatch.setRequestId(requestId);
        dispatch.setEventTime(eventTime);
        try {
            store.insertDispatch(dispatch);
        } catch (DuplicateKeyException e) {
            // 事件重复消费（如重放/补发）：已通知过，直接忽略
            log.info("检票通知批次已存在，跳过重复触发 ticketId={}", ticketId);
            return;
        }

        String varsJson = toJson(vars);
        List<NotifyRecord> records = new ArrayList<>();
        for (NotifyChannel channel : NotifyChannel.values()) {
            NotifyTemplate template = templateOf(templates, channel);
            if (template == null || !channelSwitchOn(channel)) {
                // 该通道未配模板或被开关关闭：整条通道不参与
                continue;
            }
            for (NotifyRecipient recipient : recipients) {
                NotifyRecord record = buildRecord(dispatch, template, recipient, channel,
                        ticket, order, session, vars, varsJson, operator, requestId, eventTime);
                store.insertRecord(record);
                records.add(record);
                if (RecordStatus.SKIPPED.name().equals(record.getStatus())) {
                    continue;
                }
                dispatchWithTimingPolicy(record);
            }
        }

        refreshDispatchSummary(dispatch.getId());
        log.info("检票通知批次完成 dispatchId={} ticketNo={} 记录 {} 条",
                dispatch.getId(), ticket.getTicketNo(), records.size());
    }

    /**
     * 按通道时效策略投递：
     * <ul>
     *   <li>站内信：本地库写入，始终在当前线程即时投递，毫秒级必达、当场可查；</li>
     *   <li>邮件：默认异步交独立线程池外呼，检票接口不等邮件网关（网关抖动不卡检票）；
     *       池满/线程异常/进程崩溃导致首投没发出去的，记录留 PENDING，
     *       由 {@link #retryDue()} 在首投兜底时限到期后认领补投。</li>
     * </ul>
     * evops.notify.email-async=false 可立即切回邮件同步外呼的旧行为。
     */
    private void dispatchWithTimingPolicy(NotifyRecord record) {
        if (properties.isEmailAsync() && NotifyChannel.EMAIL.name().equals(record.getChannel())) {
            boolean accepted = emailExecutor.submit(() -> {
                try {
                    // 原子认领 PENDING→SENDING：与定时兜底任务互斥，两者只有一方真正外呼
                    if (!store.claimFirstDelivery(record.getId(),
                            properties.getPendingFirstDeliverySeconds())) {
                        log.info("邮件首投已被其他执行方认领/状态已变，跳过避免重复发送 recordId={}",
                                record.getId());
                        return;
                    }
                    NotifyRecord fresh = store.getRecord(record.getId());
                    if (fresh == null || !RecordStatus.SENDING.name().equals(fresh.getStatus())) {
                        return;
                    }
                    dispatch(fresh);
                } catch (Exception e) {
                    // 不外溢：外呼异常时记录仍由 PENDING/SENDING 扫描兜底
                    log.error("邮件异步首投异常 recordId={}", record.getId(), e);
                } finally {
                    refreshDispatchSummary(record.getDispatchId());
                }
            });
            if (!accepted) {
                // 线程池已满：保留 PENDING（create_time 已落），等定时任务到点兜底首投，不丢通知
                log.warn("邮件外呼池繁忙，首投转定时兜底 recordId={} ticketNo={}",
                        record.getId(), record.getTicketNo());
            }
            return;
        }
        // 同步通道（站内信即时投递 / email-async=false 的邮件）：同样先原子占用再投递，
        // 用认领后库内重读的渲染快照，保证所有外呼口径一致、与并发重发互斥。
        if (!store.claimFirstDelivery(record.getId(), properties.getPendingFirstDeliverySeconds())) {
            log.info("记录已被其他执行方认领，跳过本次同步投递 recordId={}", record.getId());
            return;
        }
        NotifyRecord fresh = store.getRecord(record.getId());
        if (fresh != null && RecordStatus.SENDING.name().equals(fresh.getStatus())) {
            dispatch(fresh);
        }
    }

    /**
     * 定时任务：① 兜底补发"PENDING 超时未首投"的邮件；② 扫描到期 RETRYING 记录续投；
     * ③ 回收认领后崩溃、租约过期仍停在 SENDING 的记录重新投递。
     * 三类都先原子认领（CAS 占用为 SENDING）再投递，彼此及与人工重发互斥；处理完刷新所属批次汇总。
     */
    public int retryDue() {
        if (!properties.isEnabled()) {
            return 0;
        }
        java.util.Set<Long> dispatchIds = new java.util.HashSet<>();
        int processed = 0;

        // ① 首投兜底：异步外呼没真正发出去（池满拒绝/线程异常/进程在提交后崩溃）的 PENDING 邮件
        List<NotifyRecord> pendingFirst = store.pendingFirstDelivery(
                properties.getPendingFirstDeliverySeconds(), properties.getRetryBatchSize());
        for (NotifyRecord record : pendingFirst) {
            try {
                // CAS 认领，避免与正在进行的异步首投并发重复发信
                if (!store.claimFirstDelivery(record.getId(),
                        properties.getPendingFirstDeliverySeconds())) {
                    continue;
                }
                NotifyRecord fresh = store.getRecord(record.getId());
                if (fresh != null) {
                    dispatch(fresh);
                    dispatchIds.add(record.getDispatchId());
                    processed++;
                }
            } catch (Exception e) {
                log.error("检票通知首投兜底异常 recordId={}", record.getId(), e);
            }
        }

        // ② 退避到点的重试续投；③ 认领后崩溃、租约过期的 SENDING 回收续投。二者用同一把认领锁。
        List<NotifyRecord> candidates = new ArrayList<>(store.dueRetry(properties.getRetryBatchSize()));
        candidates.addAll(store.stalledSending(properties.getRetryBatchSize()));
        for (NotifyRecord record : candidates) {
            try {
                // 原子认领续投：与人工重发、其它执行方互斥，只有一方真正外呼
                if (!store.claimForRedelivery(record.getId(),
                        properties.getPendingFirstDeliverySeconds())) {
                    continue;
                }
                NotifyRecord fresh = store.getRecord(record.getId());
                if (fresh != null) {
                    dispatch(fresh);
                    dispatchIds.add(record.getDispatchId());
                    processed++;
                }
            } catch (Exception e) {
                log.error("检票通知重试/崩溃回收异常 recordId={}", record.getId(), e);
            }
        }
        for (Long dispatchId : dispatchIds) {
            refreshDispatchSummary(dispatchId);
        }
        return processed;
    }

    /**
     * 人工重发：FAILED/RETRYING/SENDING(租约过期)/PENDING 立即再投一次。
     * 所有状态都先经原子认领占用为 SENDING，抢占失败说明已有执行方在投递，直接提示而非再发一封，
     * 保证邮件这种"无接收端幂等键"的通道也不会因人工操作产生重复邮件。
     */
    public NotifyRecord manualRetry(Long recordId) {
        NotifyRecord record = store.getRecord(recordId);
        if (record == null) {
            throw new IllegalArgumentException("发送记录不存在：id=" + recordId);
        }
        if (RecordStatus.SUCCESS.name().equals(record.getStatus())) {
            throw new IllegalArgumentException("已发送成功的记录无需重发");
        }
        if (RecordStatus.SKIPPED.name().equals(record.getStatus())) {
            throw new IllegalArgumentException("跳过发送的记录（收件人无该通道地址）不能重发，请先补全收件人");
        }

        boolean claimed;
        if (RecordStatus.PENDING.name().equals(record.getStatus())) {
            // 异步首投可能正在另一线程进行：原子认领，抢不到说明已被首投/定时兜底接管
            claimed = store.claimFirstDelivery(recordId, properties.getPendingFirstDeliverySeconds());
        } else if (RecordStatus.RETRYING.name().equals(record.getStatus())) {
            // 人工要求"立即"重发（runbook 允许）：先把退避时间清零，再原子认领；
            // 若该行其实已被定时任务抢走进入 SENDING，放行 0 行、认领也失败，不会重复发
            store.expediteRetry(recordId);
            claimed = store.claimForRedelivery(recordId, properties.getPendingFirstDeliverySeconds());
        } else {
            // FAILED 随时可认领；SENDING 仅租约过期（投递线程崩溃）才可被回收，在途则失败
            claimed = store.claimForRedelivery(recordId, properties.getPendingFirstDeliverySeconds());
        }
        if (!claimed) {
            throw new IllegalStateException("该通知正在发送中，请勿重复重发；稍后刷新查看结果");
        }

        record = store.getRecord(recordId);
        if (record == null || !RecordStatus.SENDING.name().equals(record.getStatus())) {
            throw new IllegalStateException("发送记录状态已变化，请刷新后重试");
        }
        // 用认领后从库内重读的渲染快照投递，杜绝把陈旧/他人上下文的对象发出去
        dispatch(record);
        refreshDispatchSummary(record.getDispatchId());
        return store.getRecord(recordId);
    }

    /**
     * 实际投递并据结果更新记录状态。首发与重试共用。
     * 无事务：网络调用不持库连接；状态更新走 store 的独立事务。
     */
    public void dispatch(NotifyRecord record) {
        NotifyChannelSender sender = resolveSender(record.getChannel());
        if (sender == null) {
            store.updateResult(record.getId(), RecordStatus.FAILED.name(), null, null,
                    nz(record.getRetryCount()), null, "未找到通道发送器：" + record.getChannel(), false);
            return;
        }
        ChannelSendResult result = sender.send(record);
        if (result.isSuccess()) {
            store.updateResult(record.getId(), RecordStatus.SUCCESS.name(), result.getHttpStatus(),
                    result.getMessageId(), nz(record.getRetryCount()), null, null, true);
            return;
        }
        scheduleRetry(record, result.getHttpStatus(), result.getError());
    }

    private void scheduleRetry(NotifyRecord row, Integer httpStatus, String error) {
        int attempt = nz(row.getRetryCount()) + 1;
        if (attempt > properties.getMaxRetry()) {
            store.updateResult(row.getId(), RecordStatus.FAILED.name(), httpStatus, null,
                    attempt - 1, null, abbreviate(error), false);
            log.error("检票通知重试 {} 次耗尽置 FAILED recordId={} channel={} ticketNo={} 最后错误={}",
                    properties.getMaxRetry(), row.getId(), row.getChannel(), row.getTicketNo(), abbreviate(error));
            return;
        }
        LocalDateTime nextTime = LocalDateTime.now().plusSeconds(properties.backoffSeconds(attempt));
        store.updateResult(row.getId(), RecordStatus.RETRYING.name(), httpStatus, null,
                attempt, nextTime, abbreviate(error), false);
        log.warn("检票通知投递失败，第 {} 次重试排期 recordId={} at={} err={}",
                attempt, row.getId(), nextTime, abbreviate(error));
    }

    /**
     * 按记录重算批次汇总：全部通道成功 SUCCESS / 成败并存 PARTIAL / 全失败 FAILED；
     * 仍有 RETRYING 时按当前所见结果给 PARTIAL/FAILED，重试任务完成后再次刷新。
     */
    private void refreshDispatchSummary(Long dispatchId) {
        List<NotifyRecord> records = store.listByDispatch(dispatchId);
        // 通道 -> 该通道记录状态集合
        Map<String, List<String>> byChannel = new LinkedHashMap<>();
        for (NotifyRecord r : records) {
            byChannel.computeIfAbsent(r.getChannel(), k -> new ArrayList<>()).add(r.getStatus());
        }
        int planned = byChannel.size();
        int success = 0;
        int fail = 0;
        int skipped = 0;
        boolean pending = false;
        for (List<String> statuses : byChannel.values()) {
            boolean hasUnfinished = statuses.stream().anyMatch(
                    s -> RecordStatus.PENDING.name().equals(s)
                            || RecordStatus.SENDING.name().equals(s)
                            || RecordStatus.RETRYING.name().equals(s));
            boolean hasFailed = statuses.contains(RecordStatus.FAILED.name());
            boolean hasSuccess = statuses.contains(RecordStatus.SUCCESS.name());
            boolean allSkipped = statuses.stream().allMatch(s -> RecordStatus.SKIPPED.name().equals(s));
            if (hasUnfinished) {
                pending = true;
            }
            if (hasFailed) {
                fail++;
            } else if (hasSuccess && !hasUnfinished) {
                success++;
            } else if (allSkipped) {
                // 该通道收件人都不具备条件（如全员无邮箱）：不计失败，单独记跳过
                skipped++;
            }
        }
        String status;
        if (pending && success == 0 && fail == 0) {
            status = RecordStatus.RETRYING.name();
        } else if (fail == 0 && !pending && success > 0) {
            // 有通道成功、其余仅跳过（无失败）：按成功计；全部通道都跳过说明配置有误，落 FAILED 暴露问题
            status = DispatchStatus.SUCCESS.name();
        } else if (fail == 0 && !pending && success == 0 && skipped > 0) {
            status = DispatchStatus.FAILED.name();
        } else if (success > 0 || skipped > 0) {
            status = DispatchStatus.PARTIAL.name();
        } else {
            status = DispatchStatus.FAILED.name();
        }
        store.updateDispatchSummary(dispatchId, status, success, fail, planned);
    }

    // ==================== 组装 ====================

    private NotifyChannelSender resolveSender(String channel) {
        for (NotifyChannelSender sender : senderList) {
            if (channel.equals(sender.channel())) {
                return sender;
            }
        }
        return null;
    }

    private NotifyRecord buildRecord(NotifyDispatch dispatch, NotifyTemplate template,
                                     NotifyRecipient recipient, NotifyChannel channel,
                                     Ticket ticket, SaleOrder order, ShowSession session,
                                     Map<String, String> vars, String varsJson,
                                     String operator, String requestId, LocalDateTime eventTime) {
        String title = TemplateRenderer.render(template.getTitleTemplate(), vars);
        String content = TemplateRenderer.render(template.getBodyTemplate(), vars);

        NotifyRecord record = new NotifyRecord();
        record.setDispatchId(dispatch.getId());
        record.setEventCode(NotifyEventCode.TICKET_CHECK.code());
        record.setChannel(channel.name());
        record.setTemplateId(template.getId());
        record.setTemplateVersion(template.getTemplateVersion() == null ? 1 : template.getTemplateVersion());
        record.setRecipientId(recipient.getId());
        record.setRecipientName(recipient.getRecipientName());
        record.setTicketNo(ticket.getTicketNo());
        record.setOrderNo(order == null ? null : order.getOrderNo());
        record.setSessionCode(session == null ? null : session.getSessionCode());
        record.setTitleRendered(title);
        record.setContentRendered(content);
        record.setVarsSnapshot(varsJson);
        record.setStatus(RecordStatus.PENDING.name());
        record.setRetryCount(0);
        record.setMaxRetry(properties.getMaxRetry());
        record.setOperator(operator);
        record.setRequestId(requestId);
        record.setBizTime(eventTime);

        String address;
        if (channel == NotifyChannel.INBOX) {
            address = recipient.getStaffNo();
        } else {
            address = recipient.getEmail();
        }
        record.setRecipientAddress(address);
        if (address == null || address.trim().isEmpty()) {
            // 收件人不具备该通道条件：记 SKIPPED 可查，不投递不计成败
            record.setStatus(RecordStatus.SKIPPED.name());
            record.setLastError(channel == NotifyChannel.INBOX
                    ? "收件人无工号，无法投递站内信" : "收件人无邮箱，无法投递邮件");
        }
        return record;
    }

    private List<NotifyTemplate> activeTemplates(String eventCode) {
        return templateMapper.selectList(new LambdaQueryWrapper<NotifyTemplate>()
                .eq(NotifyTemplate::getEventCode, eventCode)
                .eq(NotifyTemplate::getEnabled, 1)
                .eq(NotifyTemplate::getStatus, "ACTIVE")
                .orderByAsc(NotifyTemplate::getId));
    }

    private List<NotifyRecipient> activeRecipients() {
        return recipientMapper.selectList(new LambdaQueryWrapper<NotifyRecipient>()
                .eq(NotifyRecipient::getEnabled, 1)
                .eq(NotifyRecipient::getStatus, "ACTIVE")
                .orderByAsc(NotifyRecipient::getId));
    }

    private NotifyTemplate templateOf(List<NotifyTemplate> templates, NotifyChannel channel) {
        for (NotifyTemplate template : templates) {
            if (channel.name().equals(template.getChannel())) {
                return template;
            }
        }
        return null;
    }

    private boolean channelSwitchOn(NotifyChannel channel) {
        if (channel == NotifyChannel.INBOX) {
            return properties.isInboxEnabled();
        }
        if (channel == NotifyChannel.EMAIL) {
            return properties.isEmailEnabled();
        }
        return false;
    }

    private String toJson(Map<String, String> vars) {
        try {
            return objectMapper.writeValueAsString(vars);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 1900 ? text.substring(0, 1900) : text;
    }
}
