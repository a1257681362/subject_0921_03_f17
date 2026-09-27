package com.evops.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ticket.entity.Seat;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SeatMapper extends BaseMapper<Seat> {

    /**
     * 按主键加行锁：同一座位的排期写操作在库内串行化，
     * 后进入的事务能读到先提交事务的预约区间，避免双约。
     */
    @Select("SELECT * FROM t_seat WHERE id = #{id} FOR UPDATE")
    Seat selectForUpdate(@Param("id") Long id);
}
