package com.evops.ticket.notify.service;

import com.evops.ticket.notify.entity.NotifyDispatch;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.mapper.NotifyDispatchMapper;
import com.evops.ticket.notify.mapper.NotifyRecordMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知批次/发送记录持久化门面。写操作一律 REQUIRES_NEW 独立事务：
 * 检票主事务已提交，通知侧的落库/状态更新互不连坐，网络重试期间也不持主流程连接。
 */
@Component
public class NotifyStore {

    private final NotifyDispatchMapper dispatchMapper;
    private final NotifyRecordMapper recordMapper;

    public NotifyStore(NotifyDispatchMapper dispatchMapper, NotifyRecordMapper recordMapper) {
        this.dispatchMapper = dispatchMapper;
        this.recordMapper = recordMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public NotifyDispatch insertDispatch(NotifyDispatch dispatch) {
        LocalDateTime now = LocalDateTime.now();
        dispatch.setCreateTime(now);
        dispatch.setUpdateTime(now);
        dispatchMapper.insert(dispatch);
        return dispatch;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public NotifyRecord insertRecord(NotifyRecord record) {
        LocalDateTime now = LocalDateTime.now();
        record.setCreateTime(now);
        record.setUpdateTime(now);
        recordMapper.insert(record);
        return record;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void updateResult(Long id, String status, Integer httpStatus, String messageId,
                             int retryCount, LocalDateTime nextRetryTime, String lastError,
                             boolean sent) {
        NotifyRecord r = recordMapper.selectById(id);
        if (r == null) {
            return;
        }
        r.setStatus(status);
        r.setHttpStatus(httpStatus);
        if (messageId != null) {
            r.setMessageId(messageId);
        }
        r.setRetryCount(retryCount);
        r.setNextRetryTime(nextRetryTime);
        r.setLastError(lastError);
        if (sent) {
            r.setSentTime(LocalDateTime.now());
        }
        r.setUpdateTime(LocalDateTime.now());
        recordMapper.updateById(r);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void updateDispatchSummary(Long id, String status, int success, int fail, int total) {
        NotifyDispatch d = dispatchMapper.selectById(id);
        if (d == null) {
            return;
        }
        d.setStatus(status);
        d.setSuccessChannels(success);
        d.setFailChannels(fail);
        d.setTotalChannels(total);
        d.setUpdateTime(LocalDateTime.now());
        dispatchMapper.updateById(d);
    }

    @Transactional(readOnly = true)
    public NotifyRecord getRecord(Long id) {
        return recordMapper.selectById(id);
    }

    @Transactional(readOnly = true)
    public NotifyDispatch getDispatch(Long id) {
        return dispatchMapper.selectById(id);
    }

    @Transactional(readOnly = true)
    public List<NotifyRecord> listByDispatch(Long dispatchId) {
        return recordMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NotifyRecord>()
                .eq(NotifyRecord::getDispatchId, dispatchId)
                .orderByAsc(NotifyRecord::getId));
    }

    @Transactional(readOnly = true)
    public List<NotifyRecord> dueRetry(int limit) {
        return recordMapper.selectDueRetry(LocalDateTime.now(), limit);
    }

    /** 在途崩溃兜底扫描：占用为 SENDING 但租约过期仍无结果的记录。 */
    @Transactional(readOnly = true)
    public List<NotifyRecord> stalledSending(int limit) {
        return recordMapper.selectStalledSending(LocalDateTime.now(), limit);
    }

    /** 人工立即重发：把退避未到点的 RETRYING 行放行（清零退避时间），SENDING 在途行不受影响。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean expediteRetry(Long id) {
        return recordMapper.expediteRetry(id, LocalDateTime.now()) == 1;
    }

    /** 首投兜底扫描：PENDING 邮件超过时限仍未投递的（池满拒绝/线程异常/进程崩溃）。 */
    @Transactional(readOnly = true)
    public List<NotifyRecord> pendingFirstDelivery(int pendingSeconds, int limit) {
        return recordMapper.selectPendingFirstDelivery(LocalDateTime.now().minusSeconds(pendingSeconds), limit);
    }

    /**
     * 原子认领 PENDING 首投（PENDING→RETRYING 并写崩溃兜底租约）。
     * @return true 认领成功、本次可以投递；false 已被其他线程/定时任务认领
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean claimFirstDelivery(Long id, int leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        return recordMapper.claimFirstDelivery(id, now.plusSeconds(leaseSeconds), now) == 1;
    }

    /**
     * 原子认领一次续投/人工重发（FAILED 或 退避到点的 RETRYING → 在途 RETRYING）。
     * @return true 认领成功、本次可投递；false 已被其他执行方认领或退避未到点（本次不投递）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean claimForRedelivery(Long id, int leaseSeconds) {
        LocalDateTime now = LocalDateTime.now();
        return recordMapper.claimForRedelivery(id, now, now.plusSeconds(leaseSeconds)) == 1;
    }

    public NotifyRecordMapper recordMapper() {
        return recordMapper;
    }

    public NotifyDispatchMapper dispatchMapper() {
        return dispatchMapper;
    }
}
