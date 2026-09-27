package com.evops.ticket.attachment.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 票附件元数据视图。 */
@Data
public class AttachmentVO {
    private Long id;
    private String ticketNo;
    private String fileName;
    private String contentType;
    private Long fileSize;
    private String sha256;
    private String remark;
    private String operator;
    private LocalDateTime createTime;
}
