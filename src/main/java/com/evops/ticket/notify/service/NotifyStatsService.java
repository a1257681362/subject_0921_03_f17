package com.evops.ticket.notify.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.ticket.notify.dto.stats.ChannelStatRow;
import com.evops.ticket.notify.dto.stats.NotifyStatsVO;
import com.evops.ticket.notify.entity.NotifyDispatch;
import com.evops.ticket.notify.mapper.NotifyDispatchMapper;
import com.evops.ticket.notify.mapper.NotifyRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发送结果统计：批次维度（全部成功/部分成功/全失败）+ 通道维度
 * （成功/失败/重试中/跳过计数）。供管理端看板与验收核对。
 */
@Service
public class NotifyStatsService {

    private final NotifyDispatchMapper dispatchMapper;
    private final NotifyRecordMapper recordMapper;

    public NotifyStatsService(NotifyDispatchMapper dispatchMapper, NotifyRecordMapper recordMapper) {
        this.dispatchMapper = dispatchMapper;
        this.recordMapper = recordMapper;
    }

    @Transactional(readOnly = true)
    public NotifyStatsVO stats(String eventCode, LocalDateTime from, LocalDateTime to) {
        NotifyStatsVO vo = new NotifyStatsVO();
        vo.setEventCode(eventCode);

        LambdaQueryWrapper<NotifyDispatch> wrapper = new LambdaQueryWrapper<>();
        if (eventCode != null && !eventCode.trim().isEmpty()) {
            wrapper.eq(NotifyDispatch::getEventCode, eventCode.trim());
        }
        if (from != null) {
            wrapper.ge(NotifyDispatch::getCreateTime, from);
        }
        if (to != null) {
            wrapper.lt(NotifyDispatch::getCreateTime, to);
        }
        List<NotifyDispatch> dispatches = dispatchMapper.selectList(wrapper);

        long total = dispatches.size();
        long success = 0;
        long partial = 0;
        long failed = 0;
        for (NotifyDispatch d : dispatches) {
            switch (d.getStatus()) {
                case "SUCCESS":
                    success++;
                    break;
                case "PARTIAL":
                    partial++;
                    break;
                case "FAILED":
                    failed++;
                    break;
                default:
                    // RETRYING 等中间态不计入终态三类，仍计入 total
                    break;
            }
        }
        vo.setTotalDispatches(total);
        vo.setSuccessDispatches(success);
        vo.setPartialDispatches(partial);
        vo.setFailedDispatches(failed);

        List<ChannelStatRow> rows = recordMapper.selectStats(
                eventCode == null || eventCode.trim().isEmpty() ? null : eventCode.trim(), from, to);
        Map<String, Map<String, Long>> channels = new LinkedHashMap<>();
        for (ChannelStatRow row : rows) {
            channels.computeIfAbsent(row.getChannel(), k -> {
                Map<String, Long> m = new LinkedHashMap<>();
                m.put("SUCCESS", 0L);
                m.put("FAILED", 0L);
                m.put("RETRYING", 0L);
                m.put("SKIPPED", 0L);
                m.put("TOTAL", 0L);
                return m;
            });
            Map<String, Long> bucket = channels.get(row.getChannel());
            long cnt = row.getCnt() == null ? 0 : row.getCnt();
            bucket.merge(row.getStatus(), cnt, Long::sum);
            bucket.merge("TOTAL", cnt, Long::sum);
        }
        vo.setChannels(channels);
        return vo;
    }
}
