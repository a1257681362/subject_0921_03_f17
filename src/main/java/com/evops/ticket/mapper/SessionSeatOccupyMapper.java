package com.evops.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ticket.entity.SessionSeatOccupy;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 有效座位占用。复合主键 (session_id, seat_id)，仅使用 insert 与按双列删除。
 */
public interface SessionSeatOccupyMapper extends BaseMapper<SessionSeatOccupy> {

    @Delete("DELETE FROM t_session_seat_occupy WHERE session_id = #{sessionId} AND seat_id = #{seatId}")
    int release(@Param("sessionId") Long sessionId, @Param("seatId") Long seatId);
}
