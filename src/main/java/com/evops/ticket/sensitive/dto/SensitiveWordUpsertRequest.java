package com.evops.ticket.sensitive.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 新增敏感词请求。级别 HIGH 拦截 / LOW 打码；词文本入库前统一归一，
 * 与库内已有归一形态重复时拒绝（防止同形不同写法绕过）。
 */
@Data
public class SensitiveWordUpsertRequest {

    @NotBlank(message = "敏感词不能为空")
    @Size(max = 128, message = "敏感词最长 128 字")
    private String word;

    @NotBlank(message = "级别不能为空")
    @Pattern(regexp = "HIGH|LOW", message = "级别仅支持 HIGH/LOW")
    private String level;

    @Size(max = 32, message = "分类最长 32 字")
    private String category;

    @Size(max = 255, message = "备注最长 255 字")
    private String remark;
}
