package com.evops.ticket.mask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 脱敏规则版本。规则变更只追加新版本（rule_version+1），
 * 发布时旧 ACTIVE 行置 SUPERSEDED 留档、永不改写；全表同一时刻仅一行 ACTIVE。
 */
@Data
@TableName("t_mask_rule_version")
public class MaskRuleVersion {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    public static final String DEFAULT_RULE_CODE = "CHECKIN_DEFAULT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Integer ruleVersion;
    private String ruleCode;
    /** 敏感字段 -> 打码类型 JSON：{"visitorName":"NAME","contact":"CONTACT"} */
    private String fieldRules;
    /** 可见完整值的白名单角色，逗号分隔，如 STAGE_STAFF,ADMIN */
    private String whitelistRoles;
    /** ACTIVE / SUPERSEDED */
    private String status;
    private String remark;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
