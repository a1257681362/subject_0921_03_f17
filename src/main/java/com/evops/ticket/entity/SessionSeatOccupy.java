package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 有效座位占用。主键即 (sessionId, seatId)：
 * 存在行 = 该座位在该场次被有效票持有；退票/作废物理删除即释放。
 * 仅做 insert 与自定义双列删除；@TableId 标在 sessionId 上供 MyBatis-Plus 解析表信息。
 */
@Data
@TableName("t_session_seat_occupy")
public class SessionSeatOccupy {
    @TableId(type = IdType.INPUT)
    private Long sessionId;
    private Long seatId;
    private Long ticketId;
    private Long orderId;
    private LocalDateTime createTime;
}
