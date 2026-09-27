package com.evops.ticket.attachment.service;

/**
 * 元数据存在但物理文件已丢失（被外部清理/磁盘故障）。
 * 打包时不中断整包，在清单中按"文件缺失"标注并继续后续附件。
 */
public class MissingAttachmentException extends RuntimeException {
    public MissingAttachmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
