package com.evops.auth.service;

import com.evops.auth.mapper.RefreshTokenMapper;
import com.evops.auth.mapper.TokenBlacklistMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 过期认证存证清理：
 * - 黑名单行在访问令牌自然过期后即无安全意义；
 * - 刷新令牌过期后任何重放都只会被拒绝，USED/REVOKED/过期 ACTIVE 存证均可清除。
 * 每天凌晨 03:17 执行（避开整点）。
 */
@Component
public class AuthTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenCleanupJob.class);

    private final TokenBlacklistMapper blacklistMapper;
    private final RefreshTokenMapper refreshMapper;

    public AuthTokenCleanupJob(TokenBlacklistMapper blacklistMapper, RefreshTokenMapper refreshMapper) {
        this.blacklistMapper = blacklistMapper;
        this.refreshMapper = refreshMapper;
    }

    @Scheduled(cron = "0 17 3 * * ?")
    public void cleanup() {
        LocalDateTime now = LocalDateTime.now();
        int blacklist = blacklistMapper.deleteExpired(now);
        int refresh = refreshMapper.deleteExpired(now);
        if (blacklist > 0 || refresh > 0) {
            log.info("过期认证存证清理：黑名单{}行，刷新令牌{}行", blacklist, refresh);
        }
    }
}
