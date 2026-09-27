package com.evops.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌服务端存证。明文只在登录/刷新响应中出现一次，库里仅存 SHA-256 哈希。
 *
 * 生命周期：ACTIVE --一次性轮换--> USED；登出/改密/重放连坐 --> REVOKED。
 * 同一 family_id 的一串令牌构成一台设备的轮换链；任一带外重放（已 USED 的令牌再次使用）
 * 视为泄露，整条家族未失效令牌全部 REVOKED。
 */
@Data
@TableName("t_refresh_token")
public class RefreshToken {
    public static final String ACTIVE = "ACTIVE";
    public static final String USED = "USED";
    public static final String REVOKED = "REVOKED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String tokenHash;
    private Long userId;
    private String familyId;
    /** 签发时的账号 token_version；改密后与账号不一致即拒绝 */
    private Integer tokenVersion;
    private String status;
    private LocalDateTime expiresAt;
    /**
     * 配对访问令牌（与本刷新令牌同一次登录/轮换响应里发出的 access）的 jti。
     * 一次性轮换消费本令牌时，按此把旧 access 拉黑，保证刷新后旧访问令牌立即失效。
     * V8 之前的存量行为 NULL：无配对登记，跳过拉黑，待其自然过期收敛。
     */
    private String accessJti;
    /** 配对访问令牌的自然过期时间（epoch）；已过期则不必再写黑名单 */
    private LocalDateTime accessExpiresAt;
    private LocalDateTime createTime;
    private LocalDateTime rotateTime;
    private LocalDateTime revokeTime;
}
