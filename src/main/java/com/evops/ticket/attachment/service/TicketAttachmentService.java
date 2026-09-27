package com.evops.ticket.attachment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.web.RequestContext;
import com.evops.ticket.attachment.config.AttachmentProperties;
import com.evops.ticket.attachment.dto.AttachmentVO;
import com.evops.ticket.entity.Ticket;
import com.evops.ticket.entity.TicketAttachment;
import com.evops.ticket.mapper.TicketAttachmentMapper;
import com.evops.ticket.mapper.TicketMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 票附件业务：上传登记、按票号查询、批量打包解析、逻辑删除。
 * 上传与单下载另见 {@link TicketAttachmentStorage}（物理文件）与
 * {@link TicketZipStreamer}（整包流式生成）。
 */
@Service
public class TicketAttachmentService {

    /** 批量下载按票号入参允许的最大数量（例会敲定：单包 50 份；硬上限在配置校验中兜底） */
    static final int HARD_TICKET_LIMIT = 50;

    private final TicketAttachmentMapper attachmentMapper;
    private final TicketMapper ticketMapper;
    private final TicketAttachmentStorage storage;
    private final AttachmentProperties properties;

    public TicketAttachmentService(TicketAttachmentMapper attachmentMapper, TicketMapper ticketMapper,
                                   TicketAttachmentStorage storage, AttachmentProperties properties) {
        this.attachmentMapper = attachmentMapper;
        this.ticketMapper = ticketMapper;
        this.storage = storage;
        this.properties = properties;
    }

    // ==================== 上传 ====================

    @Transactional(rollbackFor = Exception.class)
    public AttachmentVO upload(String ticketNo, MultipartFile file, String remark) {
        String normalizedTicketNo = normalizeTicketNo(ticketNo);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("附件内容为空");
        }
        Ticket ticket = requireTicket(normalizedTicketNo);
        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        if (!StringUtils.hasText(originalName)) {
            throw new IllegalArgumentException("附件文件名缺失");
        }
        long maxBytes = properties.getMaxUploadSize().toBytes();
        if (file.getSize() > maxBytes) {
            throw new IllegalArgumentException(
                    "附件超过大小上限：" + (maxBytes / 1024 / 1024) + "MB");
        }

        String storageName;
        String sha256;
        // 落盘同时算 SHA-256：MultipartFile 已由容器暂存，这里只做流式拷贝，不整文件读进内存
        try (InputStream raw = file.getInputStream();
             DigestInputStream in = new DigestInputStream(raw, MessageDigest.getInstance("SHA-256"))) {
            storageName = storage.store(in, originalName);
            sha256 = toHex(in.getMessageDigest().digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        } catch (IOException e) {
            throw new IllegalStateException("附件读取失败", e);
        }

        TicketAttachment attachment = new TicketAttachment();
        attachment.setTicketId(ticket.getId());
        attachment.setTicketNo(normalizedTicketNo);
        attachment.setFileName(originalName);
        attachment.setContentType(file.getContentType());
        attachment.setFileSize(file.getSize());
        attachment.setStorageName(storageName);
        attachment.setSha256(sha256);
        attachment.setRemark(remark);
        attachment.setOperator(RequestContext.operator());
        attachment.setRequestId(RequestContext.requestId());
        attachment.setBizTime(LocalDateTime.now());
        attachmentMapper.insert(attachment);
        return toVO(attachment);
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public List<AttachmentVO> list(String ticketNo) {
        String normalizedTicketNo = normalizeTicketNo(ticketNo);
        return listEntities(normalizedTicketNo).stream().map(this::toVO).collect(Collectors.toList());
    }

    /** 单下载取元数据（逻辑删除的不可再下载）。 */
    @Transactional(readOnly = true)
    public TicketAttachment requireAttachment(Long id) {
        TicketAttachment attachment = attachmentMapper.selectById(id);
        if (attachment == null) {
            throw new IllegalArgumentException("附件不存在或已删除");
        }
        return attachment;
    }

    /**
     * 批量打包解析：入参票号去重、去空、保序；超过单包上限直接拒。
     * 返回结果按"入参票号顺序、票号内按附件 id 排序"，保证 ZIP 目录与清单顺序稳定。
     */
    @Transactional(readOnly = true)
    public ZipSelection resolveForZip(List<String> rawTicketNos) {
        LinkedHashSet<String> ticketNos = new LinkedHashSet<>();
        if (rawTicketNos != null) {
            for (String raw : rawTicketNos) {
                if (raw != null && !raw.trim().isEmpty()) {
                    ticketNos.add(raw.trim());
                }
            }
        }
        int limit = Math.min(properties.getZipMaxTickets(), HARD_TICKET_LIMIT);
        if (ticketNos.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个票号");
        }
        if (ticketNos.size() > limit) {
            throw new IllegalArgumentException("单包最多 " + limit + " 份，本次 " + ticketNos.size()
                    + " 份，请分批下载");
        }

        List<TicketAttachment> attachments = new ArrayList<>();
        List<String> withoutAttachments = new ArrayList<>();
        for (String ticketNo : ticketNos) {
            List<TicketAttachment> ofTicket = listEntities(ticketNo);
            if (ofTicket.isEmpty()) {
                withoutAttachments.add(ticketNo);
            } else {
                attachments.addAll(ofTicket);
            }
        }
        if (attachments.isEmpty()) {
            throw new IllegalArgumentException("所选票号均无附件可下载");
        }
        return new ZipSelection(new ArrayList<>(ticketNos), attachments, withoutAttachments);
    }

    /**
     * 逻辑删除：打删除标记（deleted=id）保留元数据追溯；物理文件保留在受管目录，
     * 由运维按留存策略清理。同事务先留删除原因与审计字段，再走 MP 逻辑删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String reason) {
        TicketAttachment attachment = requireAttachment(id);
        if (StringUtils.hasText(reason)) {
            String newRemark = StringUtils.hasText(attachment.getRemark())
                    ? attachment.getRemark() + " | 删除原因:" + reason : "删除原因:" + reason;
            attachment.setRemark(newRemark);
            attachment.setOperator(RequestContext.operator());
            attachment.setRequestId(RequestContext.requestId());
            attachment.setBizTime(LocalDateTime.now());
            attachmentMapper.updateById(attachment);
        }
        // @TableLogic(delval="id")：deleteById 执行 UPDATE SET deleted=id WHERE id=? AND deleted=0
        int rows = attachmentMapper.deleteById(id);
        if (rows == 0) {
            throw new IllegalArgumentException("附件已被他人删除，请刷新后重试");
        }
    }

    // ==================== 内部 ====================

    private List<TicketAttachment> listEntities(String ticketNo) {
        LambdaQueryWrapper<TicketAttachment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TicketAttachment::getTicketNo, ticketNo).orderByAsc(TicketAttachment::getId);
        return attachmentMapper.selectList(wrapper);
    }

    private Ticket requireTicket(String ticketNo) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Ticket::getTicketNo, ticketNo);
        Ticket ticket = ticketMapper.selectOne(wrapper);
        if (ticket == null) {
            throw new IllegalArgumentException("票不存在：" + ticketNo);
        }
        return ticket;
    }

    private String normalizeTicketNo(String ticketNo) {
        if (!StringUtils.hasText(ticketNo)) {
            throw new IllegalArgumentException("票号不能为空");
        }
        return ticketNo.trim();
    }

    private AttachmentVO toVO(TicketAttachment attachment) {
        AttachmentVO vo = new AttachmentVO();
        BeanUtils.copyProperties(attachment, vo);
        return vo;
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 批量打包解析结果：有效票号顺序、命中的附件（已排序）、无附件票号。 */
    public static class ZipSelection {
        private final List<String> ticketNos;
        private final List<TicketAttachment> attachments;
        private final List<String> withoutAttachments;

        public ZipSelection(List<String> ticketNos, List<TicketAttachment> attachments,
                            List<String> withoutAttachments) {
            this.ticketNos = ticketNos;
            this.attachments = attachments;
            this.withoutAttachments = withoutAttachments;
        }

        public List<String> getTicketNos() { return ticketNos; }
        public List<TicketAttachment> getAttachments() { return attachments; }
        public List<String> getWithoutAttachments() { return withoutAttachments; }
    }
}
