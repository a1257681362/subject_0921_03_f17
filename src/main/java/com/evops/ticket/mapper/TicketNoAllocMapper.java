package com.evops.ticket.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ticket.entity.TicketNoAlloc;
import org.apache.ibatis.annotations.Select;

public interface TicketNoAllocMapper extends BaseMapper<TicketNoAlloc> {

    /** 对场次计数行加写锁，保证同场次票号分配串行。 */
    @Select("SELECT * FROM t_ticket_no_alloc WHERE session_id = #{sessionId} FOR UPDATE")
    TicketNoAlloc selectForUpdate(Long sessionId);
}
