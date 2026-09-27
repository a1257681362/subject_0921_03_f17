package com.evops.ticket.mask.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 脱敏规则版本视图（管理端）。
 */
@Data
public class MaskRuleVersionVO {
    private Long id;
    private Integer ruleVersion;
    private String ruleCode;
    /** 字段 -> 打码类型（解析后的可读映射） */
    private List<FieldRuleItem> fieldRules;
    private String fieldRulesRaw;
    private List<String> whitelistRoles;
    private String status;
    private String remark;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
