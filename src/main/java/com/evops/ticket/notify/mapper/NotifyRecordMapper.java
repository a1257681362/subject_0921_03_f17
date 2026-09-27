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
     * 在途崩溃兜底：已被占用为 SENDING、但租约（next_retry_time）已过期仍未出结果的记录
     * （外呼线程当掉、进程在认领后崩溃）。命中后仍需经原子认领重新占用再投递，不与他人重复。
     */
    @Select("SELECT * FROM t_notify_record WHERE status = 'SENDING' " +
            "AND next_retry_time IS NOT NULL AND next_retry_time <= #{now} " +
            "ORDER BY id LIMIT #{limit}")
    List<NotifyRecord> selectStalledSending(@Param("now") LocalDateTime now, @Param("limit") int limit);

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
     * 原子认领一次首投：仅当记录仍是 PENDING 时占用为 SENDING 并写下租约时间。
     * 租约 = 认领时刻 + 首投兜底时限：认领后若投递线程崩溃，行停在 SENDING，
     * 租约到期由 {@link #selectStalledSending} 扫描重新认领续投，不丢首投。
     *
     * @return 1 认领成功；0 已被别的线程认领（本次不要投递）
     */
    @Update("UPDATE t_notify_record SET status = 'SENDING', next_retry_time = #{leaseUntil}, " +
            "update_time = #{now} WHERE id = #{id} AND status = 'PENDING'")
    int claimFirstDelivery(@Param("id") Long id, @Param("leaseUntil") LocalDateTime leaseUntil,
                           @Param("now") LocalDateTime now);

    /**
     * 原子认领一次「续投/人工重发/在途崩溃补投」：可投递行（FAILED，或退避到点的 RETRYING，
     * 或租约过期的 SENDING）占用为 SENDING，任一时刻只有一个投递者认领成功。
     * 杜绝定时续投与人工重发、人工重发并发点击、崩溃补投与迟到回执相互重复外发。
     *
     * <p>注意：退避<b>未到点</b>的 RETRYING、租约<b>未过期</b>的 SENDING 不匹配（匹配 0 行），
     * 本次不得投递——人工想立即催投未到点的记录时由服务层先把退避时间清零再认领。
     *
     * @return 1 认领成功（本次可投递）；0 已被认领/退避未到点（本次不要投递）
     */
    @Update("UPDATE t_notify_record SET status = 'SENDING', next_retry_time = #{leaseUntil}, " +
            "update_time = #{now} WHERE id = #{id} AND (" +
            "status = 'FAILED' " +
            "OR (status = 'RETRYING' AND next_retry_time IS NOT NULL AND next_retry_time <= #{now}) " +
            "OR (status = 'SENDING' AND next_retry_time IS NOT NULL AND next_retry_time <= #{now})" +
            ")")
    int claimForRedelivery(@Param("id") Long id, @Param("now") LocalDateTime now,
                           @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 人工「立即重发」专用：把退避未到点的 RETRYING 行的退避时间清零，使其立即可被
     * {@link #claimForRedelivery} 认领。仅对 RETRYING 生效；SENDING（在途）行不受影响、抢不走。
     *
     * @return 1 已放行；0 该行不在 RETRYING（可能已在途/已成功/不存在）
     */
    @Update("UPDATE t_notify_record SET next_retry_time = #{now}, update_time = #{now} " +
            "WHERE id = #{id} AND status = 'RETRYING'")
    int expediteRetry(@Param("id") Long id, @Param("now") LocalDateTime now);

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
