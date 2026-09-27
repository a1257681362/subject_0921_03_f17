package com.evops.ticket.mask.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 敏感字段明文查看留痕：白名单角色每次看到完整值逐字段落一行，只追加不修改。
 * channel：API 接口返回 / EXPORT 导出，两种出口都留痕。
 */
@Data
@TableName("t_sensitive_view_log")
public class SensitiveViewLog {

    public static final String CHANNEL_API = "API";
    public static final String CHANNEL_EXPORT = "EXPORT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String roleCode;
    private String bizType;
    private String bizId;
    private String fieldName;
    private String maskType;
    private Integer ruleVersion;
    private String channel;
    private String requestPath;
    private String requestId;
    private LocalDateTime viewTime;
}
