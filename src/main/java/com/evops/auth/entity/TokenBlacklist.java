package com.evops.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 访问令牌黑名单。登出/改密时按 JWT jti 拉黑，expires_at 之后令牌自然过期、行可清理。
 */
@Data
@TableName("t_token_blacklist")
public class TokenBlacklist {
    public static final String REASON_LOGOUT = "LOGOUT";
    public static final String REASON_PASSWORD_CHANGE = "PASSWORD_CHANGE";
    /** 刷新轮换：旧 refresh 被一次性消费时，其配对的旧 access 同步拉黑 */
    public static final String REASON_ROTATED = "ROTATED";

    @TableId(type = IdType.INPUT)
    private String jti;
    private Long userId;
    private String reason;
    private LocalDateTime expiresAt;
    private LocalDateTime createTime;
}
