package com.evops.ticket.sensitive.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感词命中留痕：逐词一行，只追加不修改。
 *
 * <p>位置口径：被扫<b>原文</b>的 Unicode 码点半开区间 {@code [startCp, endCp)}，
 * 全角/插缝干扰在归一时被跳过，但命中区间仍覆盖片段内的干扰符。
 * {@code action}：BLOCKED 高危拦截（本次业务未执行）/ MASKED 低危打码放行。
 */
@Data
@TableName("t_sensitive_hit_log")
public class SensitiveHitLog {

    public static final String ACTION_BLOCKED = "BLOCKED";
    public static final String ACTION_MASKED = "MASKED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 场景：CHECK/REFUND/VOID/APPT_CANCEL */
    private String scene;
    /** 业务定位：票号/预约号/订单号 */
    private String bizRef;
    /** 被扫字段名（reason/cancelReason） */
    private String fieldName;
    private Long wordId;
    /** 词库归一形态 */
    private String word;
    /** HIGH/LOW */
    private String level;
    /** 命中位置上的原文片段（含全角/干扰符原样） */
    private String matchedText;
    private Integer startCp;
    private Integer endCp;
    /** 被扫字段完整原文（逐词冗余，按任一命中均可回查） */
    private String originalText;
    /** 打码后文本（BLOCKED 时为 null） */
    private String maskedText;
    /** BLOCKED / MASKED */
    private String action;
    private String operator;
    private String requestPath;
    private String requestId;
    private LocalDateTime hitTime;
}
