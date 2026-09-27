package com.evops.ticket.mask.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 发布脱敏规则新版本：字段映射 + 白名单角色 + 备注。
 * 发布不改写历史版本，旧版置 SUPERSEDED 留档。
 */
@Data
public class MaskRulePublishRequest {

    /** 敏感字段 -> 打码类型，至少一条 */
    @NotEmpty(message = "规则字段映射不能为空")
    @Valid
    private List<FieldRuleItem> fieldRules;

    /** 可见完整值的白名单角色（空表示全员打码） */
    private List<String> whitelistRoles;

    private String remark;
}
