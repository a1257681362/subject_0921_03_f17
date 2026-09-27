package com.evops.ticket.mask.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 脱敏规则发布请求中的一条：敏感字段 -> 打码类型。
 */
@Data
public class FieldRuleItem {

    /** 敏感字段名（VO 属性名，如 visitorName） */
    @NotBlank(message = "敏感字段名不能为空")
    private String field;

    /** 打码类型（NAME/CONTACT/GENERIC） */
    @NotBlank(message = "打码类型不能为空")
    private String maskType;
}
