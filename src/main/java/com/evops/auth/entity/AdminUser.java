package com.evops.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 场馆后台账号。密码仅存 PBKDF2 派生哈希；
 * tokenVersion 在改密时自增，旧版本 JWT 即刻失效（全端下线）。
 */
@Data
@TableName("t_admin_user")
public class AdminUser {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 登录名（工号） */
    private String username;
    private String displayName;
    /** 所属场馆，可空表示平台岗 */
    private String venue;
    /** ADMIN / MANAGER / STAFF */
    private String role;
    private String passwordHash;
    private Integer tokenVersion;
    /** ACTIVE / DISABLED */
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
