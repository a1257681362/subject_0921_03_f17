package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 演出票附件元数据（票根扫描件/PDF 等）。
 * 二进制存服务端受管目录，{@code storageName} 为服务端生成的 UUID 存储名；
 * {@code fileName} 是上传时的原始文件名（可能为中文），只用于展示与下载命名，不参与路径拼接。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_ticket_attachment")
public class TicketAttachment extends TicketBaseEntity {
    private Long ticketId;
    private String ticketNo;
    /** 原始文件名（含中文），展示/下载用 */
    private String fileName;
    private String contentType;
    private Long fileSize;
    /** 受管目录内的 UUID 存储名，与原始文件名隔离 */
    private String storageName;
    private String sha256;
    private String remark;
}
