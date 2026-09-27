package com.evops.ticket.sensitive.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/** 管理端「试扫」请求：不落业务、不留痕，仅返回命中明细，用于词库上线前验证。 */
@Data
public class SensitivePreviewRequest {

    @NotBlank(message = "试扫文本不能为空")
    @Size(max = 2000, message = "试扫文本最长 2000 字")
    private String text;
}
