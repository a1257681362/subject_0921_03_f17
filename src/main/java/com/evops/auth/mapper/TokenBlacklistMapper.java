package com.evops.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.auth.entity.TokenBlacklist;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface TokenBlacklistMapper extends BaseMapper<TokenBlacklist> {

    /** 访问令牌已过自然过期时间的黑名单行不再有安全意义，定期清理。 */
    @Delete("DELETE FROM t_token_blacklist WHERE expires_at < #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
