package com.evops.ticket.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.evops.ticket.notify.dto.stats.ChannelStatRow;
import com.evops.ticket.notify.entity.NotifyRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface NotifyRecordMapper extends BaseMapper<NotifyRecord> {

    /**
     * 扫描到期可重发记录：RETRYING 且退避到点（首发在事件线程内同步完成，不落 PENDING 等待）。
     */
    @Select("SELECT * FROM t_notify_record WHERE status = 'RETRYING' " +
            "AND next_retry_time IS NOT NULL AND next_retry_time <= #{now} " +
            "ORDER BY id LIMIT #{limit}")
    List<NotifyRecord> selectDueRetry(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 首投兜底：异步外呼模式下，记录已落 PENDING 但超过时限仍未被任何投递线程认领
     * （线程池满拒绝、外呼线程当掉、进程在提交后崩溃）。命中后仍需经
     * {@link #claimFirstDelivery} 原子认领，避免与正在进行的首投并发重复发信。
     */
    @Select("SELECT * FROM t_notify_record WHERE status = 'PENDING' AND channel = 'EMAIL' " +
            "AND create_time IS NOT NULL AND create_time <= #{cutoff} " +
            "ORDER BY id LIMIT #{limit}")
    List<NotifyRecord> selectPendingFirstDelivery(@Param("cutoff") LocalDateTime cutoff,
                                                  @Param("limit") int limit);

    /**
     * 原子认领一次首投：仅当记录仍是 PENDING 时置为 RETRYING 并写下租约时间。
     * 租约 = 认领时刻 + 首投兜底时限：认领后若投递线程崩溃，行停在 RETRYING，
     * 到期由常规重试扫描续投，不丢首投。
     *
     * @return 1 认领成功；0 已被别的线程认领（本次不要投递）
     */
    @Update("UPDATE t_notify_record SET status = 'RETRYING', next_retry_time = #{leaseUntil}, " +
            "update_time = #{now} WHERE id = #{id} AND status = 'PENDING'")
    int claimFirstDelivery(@Param("id") Long id, @Param("leaseUntil") LocalDateTime leaseUntil,
                           @Param("now") LocalDateTime now);

    /**
     * 按通道分组统计发送结果（可选时间范围/事件码过滤）。SKIPPED 不计入投递成败，单独一列。
     */
    @Select("<script>" +
            "SELECT channel, status, COUNT(*) AS cnt FROM t_notify_record " +
            "<where>" +
            "  <if test='eventCode != null'> AND event_code = #{eventCode}</if>" +
            "  <if test='from != null'> AND create_time &gt;= #{from}</if>" +
            "  <if test='to != null'> AND create_time &lt; #{to}</if>" +
            "</where>" +
            "GROUP BY channel, status ORDER BY channel, status" +
            "</script>")
    List<ChannelStatRow> selectStats(@Param("eventCode") String eventCode,
                                     @Param("from") LocalDateTime from,
                                     @Param("to") LocalDateTime to);
}
