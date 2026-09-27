package com.evops.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.auth.entity.RefreshToken;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    /**
     * 一次性消费：仅当行仍为 ACTIVE 时翻为 USED。
     * 返回 0 即令牌已被用过/撤销/不存在，调用方按重放处理（家族连坐）。
     */
    @Update("UPDATE t_refresh_token SET status = 'USED', rotate_time = #{now} "
            + "WHERE token_hash = #{tokenHash} AND status = 'ACTIVE'")
    int consume(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    /** 重放连坐：撤销同家族（同一登录设备链）全部未失效刷新令牌。 */
    @Update("UPDATE t_refresh_token SET status = 'REVOKED', revoke_time = #{now} "
            + "WHERE family_id = #{familyId} AND status = 'ACTIVE'")
    int revokeFamilyActive(@Param("familyId") String familyId, @Param("now") LocalDateTime now);

    /** 改密全端下线：撤销该账号所有设备的未失效刷新令牌。 */
    @Update("UPDATE t_refresh_token SET status = 'REVOKED', revoke_time = #{now} "
            + "WHERE user_id = #{userId} AND status = 'ACTIVE'")
    int revokeUserActive(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /** 登出：撤销本设备当前刷新令牌（幂等，非 ACTIVE 不影响行数语义）。 */
    @Update("UPDATE t_refresh_token SET status = 'REVOKED', revoke_time = #{now} "
            + "WHERE id = #{id} AND status = 'ACTIVE'")
    int revokeById(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 清理过期且已终结（USED/REVOKED）的存证行：
     * 过期后任何重放都只会得到“已过期”，存证不再需要；ACTIVE 但已过期的行也一并删除。
     */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM t_refresh_token WHERE expires_at < #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
