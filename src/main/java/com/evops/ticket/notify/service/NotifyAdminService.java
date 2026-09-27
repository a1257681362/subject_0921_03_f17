package com.evops.ticket.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.ticket.notify.dto.NotifyRecordVO;
import com.evops.ticket.notify.dto.NotifyRecipientVO;
import com.evops.ticket.notify.dto.NotifyTemplateVO;
import com.evops.ticket.notify.dto.RecipientUpsertRequest;
import com.evops.ticket.notify.dto.TemplateUpsertRequest;
import com.evops.ticket.notify.entity.NotifyRecord;
import com.evops.ticket.notify.entity.NotifyRecipient;
import com.evops.ticket.notify.entity.NotifyTemplate;
import com.evops.ticket.notify.mapper.NotifyRecipientMapper;
import com.evops.ticket.notify.mapper.NotifyTemplateMapper;
import com.evops.ticket.notify.service.NotifyStore;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 通知后台管理：模板维护（改动 tplVersion+1，不改历史发送记录）、
 * 场务收件人维护、发送记录/批次回查。
 */
@Service
public class NotifyAdminService {

    private final NotifyTemplateMapper templateMapper;
    private final NotifyRecipientMapper recipientMapper;
    private final NotifyStore store;

    public NotifyAdminService(NotifyTemplateMapper templateMapper,
                              NotifyRecipientMapper recipientMapper, NotifyStore store) {
        this.templateMapper = templateMapper;
        this.recipientMapper = recipientMapper;
        this.store = store;
    }

    // ==================== 模板 ====================

    @Transactional(readOnly = true)
    public PageVO<NotifyTemplateVO> pageTemplates(long page, long size, String eventCode, String channel) {
        LambdaQueryWrapper<NotifyTemplate> w = new LambdaQueryWrapper<>();
        if (eventCode != null && !eventCode.trim().isEmpty()) {
            w.eq(NotifyTemplate::getEventCode, eventCode.trim());
        }
        if (channel != null && !channel.trim().isEmpty()) {
            w.eq(NotifyTemplate::getChannel, channel.trim());
        }
        w.orderByAsc(NotifyTemplate::getEventCode).orderByAsc(NotifyTemplate::getChannel)
                .orderByDesc(NotifyTemplate::getId);
        Page<NotifyTemplate> result = templateMapper.selectPage(new Page<>(page, size), w);
        List<NotifyTemplateVO> list = result.getRecords().stream()
                .map(this::toTemplateVO).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    /** 新建模板；同 模板编码+通道 已存在（未删除）拒绝，避免误覆盖历史版本口径。 */
    @Transactional(rollbackFor = Exception.class)
    public NotifyTemplateVO createTemplate(TemplateUpsertRequest req) {
        Long exists = templateMapper.selectCount(new LambdaQueryWrapper<NotifyTemplate>()
                .eq(NotifyTemplate::getTemplateCode, req.getTemplateCode().trim())
                .eq(NotifyTemplate::getChannel, req.getChannel()));
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("同编码同通道模板已存在，请改用修改（会自动升版本）");
        }
        NotifyTemplate t = new NotifyTemplate();
        t.setTemplateCode(req.getTemplateCode().trim());
        t.setTemplateName(req.getTemplateName());
        t.setEventCode(req.getEventCode().trim());
        t.setChannel(req.getChannel());
        t.setTitleTemplate(req.getTitleTemplate());
        t.setBodyTemplate(req.getBodyTemplate());
        t.setEnabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        t.setTemplateVersion(1);
        t.setStatus("ACTIVE");
        t.setRemark(req.getRemark());
        templateMapper.insert(t);
        return toTemplateVO(t);
    }

    /**
     * 修改模板：标题/正文/事件码变化时 tplVersion+1；启用状态、备注、名称变化不升版。
     * 已发出的记录在发送当时已快照标题/正文与版本号，本次修改对历史内容零影响。
     */
    @Transactional(rollbackFor = Exception.class)
    public NotifyTemplateVO updateTemplate(Long id, TemplateUpsertRequest req) {
        NotifyTemplate t = templateMapper.selectById(id);
        if (t == null) {
            throw new IllegalArgumentException("模板不存在：id=" + id);
        }
        if (!t.getChannel().equals(req.getChannel()) || !t.getTemplateCode().equals(req.getTemplateCode().trim())) {
            throw new IllegalArgumentException("模板编码与通道不可修改（保证历史发送记录可回溯）");
        }
        boolean contentChanged = !Objects.equals(t.getTitleTemplate(), req.getTitleTemplate())
                || !Objects.equals(t.getBodyTemplate(), req.getBodyTemplate())
                || !Objects.equals(t.getEventCode(), req.getEventCode().trim());
        t.setTemplateName(req.getTemplateName());
        t.setEventCode(req.getEventCode().trim());
        t.setTitleTemplate(req.getTitleTemplate());
        t.setBodyTemplate(req.getBodyTemplate());
        t.setEnabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        t.setRemark(req.getRemark());
        t.setStatus("ACTIVE");
        if (contentChanged) {
            t.setTemplateVersion((t.getTemplateVersion() == null ? 1 : t.getTemplateVersion()) + 1);
        }
        int rows = templateMapper.updateById(t);
        if (rows == 0) {
            throw new IllegalArgumentException("模板已被他人变更，请刷新后重试");
        }
        return toTemplateVO(t);
    }

    // ==================== 收件人 ====================

    @Transactional(readOnly = true)
    public PageVO<NotifyRecipientVO> pageRecipients(long page, long size, String roleCode, String keyword) {
        LambdaQueryWrapper<NotifyRecipient> w = new LambdaQueryWrapper<>();
        if (roleCode != null && !roleCode.trim().isEmpty()) {
            w.eq(NotifyRecipient::getRoleCode, roleCode.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            w.and(q -> q.like(NotifyRecipient::getRecipientName, kw)
                    .or().like(NotifyRecipient::getStaffNo, kw)
                    .or().like(NotifyRecipient::getEmail, kw));
        }
        w.orderByAsc(NotifyRecipient::getId);
        Page<NotifyRecipient> result = recipientMapper.selectPage(new Page<>(page, size), w);
        List<NotifyRecipientVO> list = result.getRecords().stream()
                .map(this::toRecipientVO).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    @Transactional(rollbackFor = Exception.class)
    public NotifyRecipientVO createRecipient(RecipientUpsertRequest req) {
        if ((req.getStaffNo() == null || req.getStaffNo().trim().isEmpty())
                && (req.getEmail() == null || req.getEmail().trim().isEmpty())) {
            throw new IllegalArgumentException("工号与邮箱至少填一个，否则两个通道都无法投递");
        }
        if (req.getStaffNo() != null && !req.getStaffNo().trim().isEmpty()) {
            Long exists = recipientMapper.selectCount(new LambdaQueryWrapper<NotifyRecipient>()
                    .eq(NotifyRecipient::getStaffNo, req.getStaffNo().trim()));
            if (exists != null && exists > 0) {
                throw new IllegalArgumentException("同工号收件人已存在：" + req.getStaffNo());
            }
        }
        NotifyRecipient r = new NotifyRecipient();
        r.setRecipientName(req.getRecipientName());
        r.setStaffNo(blankToNull(req.getStaffNo()));
        r.setEmail(blankToNull(req.getEmail()));
        r.setRoleCode(req.getRoleCode() == null ? "STAGE_STAFF" : req.getRoleCode());
        r.setEnabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        r.setStatus("ACTIVE");
        r.setRemark(req.getRemark());
        recipientMapper.insert(r);
        return toRecipientVO(r);
    }

    @Transactional(rollbackFor = Exception.class)
    public NotifyRecipientVO updateRecipient(Long id, RecipientUpsertRequest req) {
        NotifyRecipient r = recipientMapper.selectById(id);
        if (r == null) {
            throw new IllegalArgumentException("收件人不存在：id=" + id);
        }
        if ((req.getStaffNo() == null || req.getStaffNo().trim().isEmpty())
                && (req.getEmail() == null || req.getEmail().trim().isEmpty())) {
            throw new IllegalArgumentException("工号与邮箱至少保留一个");
        }
        if (req.getStaffNo() != null && !req.getStaffNo().trim().isEmpty()) {
            Long dup = recipientMapper.selectCount(new LambdaQueryWrapper<NotifyRecipient>()
                    .eq(NotifyRecipient::getStaffNo, req.getStaffNo().trim())
                    .ne(NotifyRecipient::getId, id));
            if (dup != null && dup > 0) {
                throw new IllegalArgumentException("同工号收件人已存在：" + req.getStaffNo());
            }
        }
        r.setRecipientName(req.getRecipientName());
        r.setStaffNo(blankToNull(req.getStaffNo()));
        r.setEmail(blankToNull(req.getEmail()));
        r.setRoleCode(req.getRoleCode() == null ? "STAGE_STAFF" : req.getRoleCode());
        r.setEnabled(Boolean.FALSE.equals(req.getEnabled()) ? 0 : 1);
        r.setStatus("ACTIVE");
        r.setRemark(req.getRemark());
        int rows = recipientMapper.updateById(r);
        if (rows == 0) {
            throw new IllegalArgumentException("收件人已被他人变更，请刷新后重试");
        }
        return toRecipientVO(r);
    }

    // ==================== 发送记录 ====================

    @Transactional(readOnly = true)
    public PageVO<NotifyRecordVO> pageRecords(long page, long size, String eventCode, String channel,
                                              String status, String ticketNo, Long dispatchId) {
        LambdaQueryWrapper<NotifyRecord> w = new LambdaQueryWrapper<>();
        if (eventCode != null && !eventCode.trim().isEmpty()) {
            w.eq(NotifyRecord::getEventCode, eventCode.trim());
        }
        if (channel != null && !channel.trim().isEmpty()) {
            w.eq(NotifyRecord::getChannel, channel.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            w.eq(NotifyRecord::getStatus, status.trim());
        }
        if (ticketNo != null && !ticketNo.trim().isEmpty()) {
            w.eq(NotifyRecord::getTicketNo, ticketNo.trim());
        }
        if (dispatchId != null) {
            w.eq(NotifyRecord::getDispatchId, dispatchId);
        }
        w.orderByDesc(NotifyRecord::getId);
        Page<NotifyRecord> result = store.recordMapper().selectPage(new Page<>(page, size), w);
        List<NotifyRecordVO> list = result.getRecords().stream()
                .map(this::toRecordVO).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    @Transactional(readOnly = true)
    public NotifyRecordVO getRecord(Long id) {
        NotifyRecord r = store.getRecord(id);
        if (r == null) {
            throw new IllegalArgumentException("发送记录不存在：id=" + id);
        }
        return toRecordVO(r);
    }

    // ==================== 转换 ====================

    private NotifyTemplateVO toTemplateVO(NotifyTemplate t) {
        NotifyTemplateVO vo = new NotifyTemplateVO();
        BeanUtils.copyProperties(t, vo);
        return vo;
    }

    private NotifyRecipientVO toRecipientVO(NotifyRecipient r) {
        NotifyRecipientVO vo = new NotifyRecipientVO();
        BeanUtils.copyProperties(r, vo);
        return vo;
    }

    private NotifyRecordVO toRecordVO(NotifyRecord r) {
        NotifyRecordVO vo = new NotifyRecordVO();
        BeanUtils.copyProperties(r, vo);
        return vo;
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}
