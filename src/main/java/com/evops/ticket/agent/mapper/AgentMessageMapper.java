package com.evops.ticket.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ticket.agent.entity.AgentMessage;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentMessageMapper extends BaseMapper<AgentMessage> {

    /**
     * 扫描到期可发的出站报文：PENDING（到点首发）或 RETRYING（退避到期）。
     * 用独立 SQL 显式过滤，避免包装状态与 next_retry_time 为 null 的判断歧义。
     */
    @Select("SELECT * FROM t_agent_message WHERE direction = 'OUTBOUND' " +
            "AND (status = 'PENDING' OR (status = 'RETRYING' AND next_retry_time IS NOT NULL " +
            "AND next_retry_time <= #{now})) ORDER BY id LIMIT #{limit}")
    List<AgentMessage> selectDueOutbound(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
