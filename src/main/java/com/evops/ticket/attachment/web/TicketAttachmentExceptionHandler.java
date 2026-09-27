package com.evops.ticket.attachment.web;

import com.evops.common.ApiResponse;
import com.evops.ticket.attachment.service.MissingAttachmentException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 附件相关异常单独成文，优先级高于全局兜底：
 * 上传超 multipart 上限给明确文案；物理文件缺失提示查存储目录。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TicketAttachmentExceptionHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ApiResponse.fail("附件超过大小上限，请压缩或拆分后重试");
    }

    @ExceptionHandler(MissingAttachmentException.class)
    public ApiResponse<Void> handleMissing(MissingAttachmentException ex) {
        return ApiResponse.fail(ex.getMessage());
    }
}
