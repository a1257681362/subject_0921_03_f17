package com.evops.ticket.sensitive.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分级敏感词库条目。normalized_word 为归一形态（NFKC+小写+去干扰），同形唯一；
 * status=ENABLED 才计入 DFA，停用仅留档不物理删除。
 */
@Data
@TableName("t_sensitive_word")
public class SensitiveWord {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 词库原文（展示/录入口径） */
    private String word;
    /** 归一形态（DFA 实际匹配键） */
    private String normalizedWord;
    /** HIGH 高危拦截 / LOW 低危打码 */
    private String level;
    /** 词分类：FRAUD/PORN/GAMBLING/SCALPER/ILLEGAL/AD */
    private String category;
    /** ENABLED / DISABLED */
    private String status;
    private String remark;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
