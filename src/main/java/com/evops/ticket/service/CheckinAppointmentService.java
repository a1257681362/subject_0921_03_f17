package com.evops.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.checkin.AppointmentConflictVO;
import com.evops.ticket.dto.checkin.AppointmentCreateRequest;
import com.evops.ticket.dto.checkin.AppointmentVO;
import com.evops.ticket.dto.checkin.FreeSlotVO;
import com.evops.ticket.dto.checkin.SeatOccupancyVO;
import com.evops.ticket.entity.CheckinAppointment;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.enums.AppointmentStatus;
import com.evops.ticket.mapper.CheckinAppointmentMapper;
import com.evops.ticket.mapper.SeatMapper;
import com.evops.ticket.sensitive.SensitiveScene;
import com.evops.ticket.sensitive.service.SensitiveScanService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 检票排期：预约按半开区间 [start, end) 存储。
 *
 * 冲突规则（同一座位，仅 ACTIVE 行参与）：
 * 1. 区间重叠；
 * 2. 相邻预约首尾间隔不足 {@link #BUFFER_MINUTES} 分钟。
 * 即既有 [a,b) 与新约 [s,e) 冲突当且仅当 {@code s < b+20m 且 a < e+20m}。
 *
 * 取消置 CANCELLED 立即释放；写操作先对座位行加 FOR UPDATE 锁按座位串行，
 * 保证"取消提交后可立即再约"，且并发双约恰好一条成功。
 */
@Service
public class CheckinAppointmentService {

    /** 相邻预约最小间隔（分钟），不足即冲突 */
    public static final int BUFFER_MINUTES = 20;
    /** 空档推荐向前回溯的窗口天数 */
    private static final int SUGGEST_WINDOW_DAYS = 3;
    /** 空档推荐最多返回条数 */
    private static final int SUGGEST_LIMIT = 5;

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CheckinAppointmentMapper appointmentMapper;
    private final SeatMapper seatMapper;
    private final SensitiveScanService sensitiveScanService;

    public CheckinAppointmentService(CheckinAppointmentMapper appointmentMapper, SeatMapper seatMapper,
                                    SensitiveScanService sensitiveScanService) {
        this.appointmentMapper = appointmentMapper;
        this.seatMapper = seatMapper;
        this.sensitiveScanService = sensitiveScanService;
    }

    // ==================== 预约 ====================

    @Transactional(rollbackFor = Exception.class)
    public AppointmentVO create(AppointmentCreateRequest req) {
        LocalDateTime start = req.getStartTime();
        LocalDateTime end = req.getEndTime();
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }

        // 锁座位行：同座位排期写操作库内串行，先提交的区间对后进入者可见
        Seat seat = lockSeat(req.getSeatId());

        List<CheckinAppointment> active = findActiveCandidates(seat.getId(), start, end);
        List<CheckinAppointment> conflicts = new ArrayList<>();
        for (CheckinAppointment appt : active) {
            if (isConflicting(appt.getStartTime(), appt.getEndTime(), start, end)) {
                conflicts.add(appt);
            }
        }
        if (!conflicts.isEmpty()) {
            throw new AppointmentConflictException(
                    "该座位所选时段已被预约或与相邻预约间隔不足" + BUFFER_MINUTES + "分钟",
                    buildConflict(seat, start, end, conflicts));
        }

        LocalDateTime bizTime = req.getBizTime() != null ? req.getBizTime() : LocalDateTime.now();
        CheckinAppointment appt = new CheckinAppointment();
        appt.setAppointmentNo(generateNo());
        appt.setSeatId(seat.getId());
        appt.setVisitorName(req.getVisitorName());
        appt.setContact(req.getContact());
        appt.setStartTime(start);
        appt.setEndTime(end);
        appt.setStatus(AppointmentStatus.ACTIVE.name());
        appt.setOperator(RequestContext.operator());
        appt.setRequestId(RequestContext.requestId());
        appt.setBizTime(bizTime);
        appointmentMapper.insert(appt);
        return toVO(appt, seat);
    }

    /** 取消即释放：置 CANCELLED 后该区间立即不再参与冲突，可再约。 */
    @Transactional(rollbackFor = Exception.class)
    public AppointmentVO cancel(Long id, String reason, LocalDateTime bizTimeIn) {
        CheckinAppointment appt = appointmentMapper.selectById(id);
        if (appt == null) {
            throw new IllegalArgumentException("预约不存在");
        }
        if (AppointmentStatus.CANCELLED.name().equals(appt.getStatus())) {
            throw new IllegalArgumentException("预约已取消");
        }

        // 与预约同一把座位锁：取消先提交则随后进入的预约能看到释放结果
        lockSeat(appt.getSeatId());

        // 取消原因过敏感词闸门：高危抛出回滚（状态尚未改），低危打码后落库
        reason = sensitiveScanService.scan(
                SensitiveScene.APPT_CANCEL, appt.getAppointmentNo(), "cancelReason", reason).getText();

        LocalDateTime bizTime = bizTimeIn != null ? bizTimeIn : LocalDateTime.now();
        appt.setStatus(AppointmentStatus.CANCELLED.name());
        appt.setCancelReason(reason);
        appt.setOperator(RequestContext.operator());
        appt.setRequestId(RequestContext.requestId());
        appt.setBizTime(bizTime);
        int rows = appointmentMapper.updateById(appt);
        if (rows == 0) {
            throw new IllegalArgumentException("预约已被他人变更，请刷新后重试");
        }
        Seat seat = seatMapper.selectById(appt.getSeatId());
        return toVO(appt, seat);
    }

    // ==================== 查询 ====================

    /**
     * 导出/大批量取数：按与分页接口相同的过滤条件取至多 {@code maxRows} 条 ACTIVE/全状态预约，
     * 时间正序。仅用于检票单据导出（敏感列在导出边界统一脱敏），不做分页。
     */
    @Transactional(readOnly = true)
    public List<AppointmentVO> listForExport(Long seatId, String status,
                                             LocalDateTime from, LocalDateTime to, long maxRows) {
        LambdaQueryWrapper<CheckinAppointment> wrapper = new LambdaQueryWrapper<>();
        if (seatId != null) {
            wrapper.eq(CheckinAppointment::getSeatId, seatId);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(CheckinAppointment::getStatus, status.trim());
        }
        if (from != null) {
            wrapper.gt(CheckinAppointment::getEndTime, from);
        }
        if (to != null) {
            wrapper.lt(CheckinAppointment::getStartTime, to);
        }
        wrapper.orderByAsc(CheckinAppointment::getStartTime).orderByAsc(CheckinAppointment::getId);
        wrapper.last("LIMIT " + maxRows);
        List<CheckinAppointment> records = appointmentMapper.selectList(wrapper);

        List<Long> seatIds = records.stream()
                .map(CheckinAppointment::getSeatId).distinct().collect(Collectors.toList());
        java.util.Map<Long, Seat> seatMap = seatIds.isEmpty()
                ? java.util.Collections.emptyMap()
                : seatMapper.selectBatchIds(seatIds).stream()
                        .collect(Collectors.toMap(Seat::getId, s -> s, (a, b) -> a));
        return records.stream()
                .map(a -> toVO(a, seatMap.get(a.getSeatId())))
                .collect(Collectors.toList());
    }

    /** 按过滤条件统计总数（导出前先校验行数上限，超限时在打开响应流前拒绝）。 */
    @Transactional(readOnly = true)
    public long countForExport(Long seatId, String status, LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<CheckinAppointment> wrapper = new LambdaQueryWrapper<>();
        if (seatId != null) {
            wrapper.eq(CheckinAppointment::getSeatId, seatId);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(CheckinAppointment::getStatus, status.trim());
        }
        if (from != null) {
            wrapper.gt(CheckinAppointment::getEndTime, from);
        }
        if (to != null) {
            wrapper.lt(CheckinAppointment::getStartTime, to);
        }
        Long count = appointmentMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    @Transactional(readOnly = true)
    public AppointmentVO get(Long id) {
        CheckinAppointment appt = appointmentMapper.selectById(id);
        if (appt == null) {
            throw new IllegalArgumentException("预约不存在");
        }
        return toVO(appt, seatMapper.selectById(appt.getSeatId()));
    }

    /**
     * 预约明细分页：可按座位 / 状态 / 时间区间（与预约区间相交）过滤。
     */
    @Transactional(readOnly = true)
    public PageVO<AppointmentVO> page(long page, long size, Long seatId, String status,
                                      LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<CheckinAppointment> wrapper = new LambdaQueryWrapper<>();
        if (seatId != null) {
            wrapper.eq(CheckinAppointment::getSeatId, seatId);
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(CheckinAppointment::getStatus, status.trim());
        }
        if (from != null) {
            wrapper.gt(CheckinAppointment::getEndTime, from);
        }
        if (to != null) {
            wrapper.lt(CheckinAppointment::getStartTime, to);
        }
        wrapper.orderByAsc(CheckinAppointment::getStartTime).orderByAsc(CheckinAppointment::getId);
        Page<CheckinAppointment> result = appointmentMapper.selectPage(new Page<>(page, size), wrapper);

        // 批量补座位位置信息，避免逐行查询
        List<Long> seatIds = result.getRecords().stream()
                .map(CheckinAppointment::getSeatId)
                .distinct()
                .collect(Collectors.toList());
        java.util.Map<Long, Seat> seatMap = seatIds.isEmpty()
                ? java.util.Collections.emptyMap()
                : seatMapper.selectBatchIds(seatIds).stream()
                        .collect(Collectors.toMap(Seat::getId, s -> s, (a, b) -> a));
        List<AppointmentVO> list = result.getRecords().stream()
                .map(a -> toVO(a, seatMap.get(a.getSeatId())))
                .collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    /**
     * 查询某座位在 [rangeStart, rangeEnd) 内的有效占用（可查每座位的占用情况）。
     */
    @Transactional(readOnly = true)
    public SeatOccupancyVO occupancy(Long seatId, LocalDateTime rangeStart, LocalDateTime rangeEnd) {
        Seat seat = requireSeat(seatId);
        if (rangeStart == null || rangeEnd == null || !rangeEnd.isAfter(rangeStart)) {
            throw new IllegalArgumentException("查询区间非法：start/end 必填且 end 需晚于 start");
        }
        LambdaQueryWrapper<CheckinAppointment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CheckinAppointment::getSeatId, seatId)
                .eq(CheckinAppointment::getStatus, AppointmentStatus.ACTIVE.name())
                .gt(CheckinAppointment::getEndTime, rangeStart)
                .lt(CheckinAppointment::getStartTime, rangeEnd)
                .orderByAsc(CheckinAppointment::getStartTime);
        List<AppointmentVO> appts = appointmentMapper.selectList(wrapper).stream()
                .map(a -> toVO(a, seat))
                .collect(Collectors.toList());

        SeatOccupancyVO vo = new SeatOccupancyVO();
        vo.setSeatId(seat.getId());
        vo.setVenue(seat.getVenue());
        vo.setArea(seat.getArea());
        vo.setRowNo(seat.getRowNo());
        vo.setSeatNo(seat.getSeatNo());
        vo.setRangeStart(rangeStart);
        vo.setRangeEnd(rangeEnd);
        vo.setAppointments(appts);
        return vo;
    }

    /**
     * 空档推荐：以请求时段为基准，在其所在自然日起的 {@value #SUGGEST_WINDOW_DAYS} 天窗口内，
     * 找出能容纳 durationMinutes、且与所有 ACTIVE 预约满足 20 分钟间隔的空档，按距请求开始时间就近排序。
     */
    @Transactional(readOnly = true)
    public List<FreeSlotVO> suggestFreeSlots(Long seatId, LocalDateTime wantedStart,
                                             LocalDateTime wantedEnd, Integer durationMinutes) {
        Seat seat = requireSeat(seatId);
        if (wantedStart == null || wantedEnd == null || !wantedEnd.isAfter(wantedStart)) {
            throw new IllegalArgumentException("目标时段非法：start/end 必填且 end 需晚于 start");
        }
        long duration = durationMinutes != null && durationMinutes > 0
                ? durationMinutes
                : Duration.between(wantedStart, wantedEnd).toMinutes();
        if (duration <= 0) {
            throw new IllegalArgumentException("预约时长必须为正数分钟");
        }

        LocalDateTime windowStart = wantedStart.toLocalDate().atStartOfDay();
        LocalDateTime windowEnd = windowStart.plusDays(SUGGEST_WINDOW_DAYS);
        // 候选预约：缓冲扩展后仍与窗口相交的 ACTIVE 行
        LambdaQueryWrapper<CheckinAppointment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CheckinAppointment::getSeatId, seatId)
                .eq(CheckinAppointment::getStatus, AppointmentStatus.ACTIVE.name())
                .gt(CheckinAppointment::getEndTime, windowStart.minusMinutes(BUFFER_MINUTES))
                .lt(CheckinAppointment::getStartTime, windowEnd.plusMinutes(BUFFER_MINUTES))
                .orderByAsc(CheckinAppointment::getStartTime);

        // 阻塞区间 = 有效预约向两侧各扩 20 分钟缓冲，与窗口求交后合并
        List<LocalDateTime[]> ranges = new ArrayList<>();
        for (CheckinAppointment a : appointmentMapper.selectList(wrapper)) {
            LocalDateTime bStart = a.getStartTime().minusMinutes(BUFFER_MINUTES);
            LocalDateTime bEnd = a.getEndTime().plusMinutes(BUFFER_MINUTES);
            if (bStart.isBefore(windowStart)) {
                bStart = windowStart;
            }
            if (bEnd.isAfter(windowEnd)) {
                bEnd = windowEnd;
            }
            if (bEnd.isAfter(bStart)) {
                ranges.add(new LocalDateTime[]{bStart, bEnd});
            }
        }
        ranges.sort(Comparator.comparing(r -> r[0]));

        List<FreeSlotVO> gaps = new ArrayList<>();
        LocalDateTime cursor = windowStart;
        for (LocalDateTime[] r : ranges) {
            if (r[0].isAfter(cursor)) {
                addGapIfFits(gaps, cursor, r[0], duration, wantedStart);
            }
            if (r[1].isAfter(cursor)) {
                cursor = r[1];
            }
        }
        addGapIfFits(gaps, cursor, windowEnd, duration, wantedStart);

        gaps.sort(Comparator.comparingLong(g -> Math.abs(Duration.between(wantedStart, g.getStartTime()).toMinutes())));
        return gaps.stream().limit(SUGGEST_LIMIT).collect(Collectors.toList());
    }

    // ==================== 规则与组装 ====================

    /**
     * 半开区间冲突判定：重叠或相邻间隔不足缓冲。
     * 既有 [a,b)、新约 [s,e)，互不冲突要求 s ≥ b+缓冲 或 a ≥ e+缓冲；取反即冲突。
     */
    private boolean isConflicting(LocalDateTime a, LocalDateTime b,
                                  LocalDateTime s, LocalDateTime e) {
        return s.isBefore(b.plusMinutes(BUFFER_MINUTES))
                && a.isBefore(e.plusMinutes(BUFFER_MINUTES));
    }

    /**
     * 取与 [start,end) 可能冲突的 ACTIVE 候选：
     * start_time < end+缓冲 且 end_time > start-缓冲，在 Java 侧再做精确判定。
     */
    private List<CheckinAppointment> findActiveCandidates(Long seatId,
                                                          LocalDateTime start, LocalDateTime end) {
        LambdaQueryWrapper<CheckinAppointment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CheckinAppointment::getSeatId, seatId)
                .eq(CheckinAppointment::getStatus, AppointmentStatus.ACTIVE.name())
                .lt(CheckinAppointment::getStartTime, end.plusMinutes(BUFFER_MINUTES))
                .gt(CheckinAppointment::getEndTime, start.minusMinutes(BUFFER_MINUTES));
        return appointmentMapper.selectList(wrapper);
    }

    private void addGapIfFits(List<FreeSlotVO> gaps, LocalDateTime gapStart,
                              LocalDateTime gapEnd, long durationMinutes,
                              LocalDateTime wantedStart) {
        if (!Duration.between(gapStart, gapEnd).minusMinutes(durationMinutes).isNegative()) {
            // 空档起点向目标开始时间靠拢：落在该区间内的任意起点都满足间隔要求
            LocalDateTime slotStart = wantedStart;
            if (slotStart.isBefore(gapStart)) {
                slotStart = gapStart;
            } else if (slotStart.plusMinutes(durationMinutes).isAfter(gapEnd)) {
                slotStart = gapEnd.minusMinutes(durationMinutes);
            }
            gaps.add(new FreeSlotVO(slotStart, slotStart.plusMinutes(durationMinutes)));
        }
    }

    private AppointmentConflictVO buildConflict(Seat seat, LocalDateTime start, LocalDateTime end,
                                                List<CheckinAppointment> conflicts) {
        AppointmentConflictVO vo = new AppointmentConflictVO();
        vo.setSeatId(seat.getId());
        vo.setVenue(seat.getVenue());
        vo.setArea(seat.getArea());
        vo.setRowNo(seat.getRowNo());
        vo.setSeatNo(seat.getSeatNo());
        vo.setRequestedStart(start);
        vo.setRequestedEnd(end);
        vo.setBufferMinutes(BUFFER_MINUTES);
        vo.setConflicts(conflicts.stream().map(a -> toVO(a, seat)).collect(Collectors.toList()));
        vo.setSuggestedSlots(suggestFreeSlots(seat.getId(), start, end, null));
        return vo;
    }

    private Seat lockSeat(Long seatId) {
        Seat seat = seatMapper.selectForUpdate(seatId);
        if (seat == null || (seat.getDeleted() != null && seat.getDeleted() != 0L)) {
            throw new IllegalArgumentException("座位不存在：seatId=" + seatId);
        }
        return seat;
    }

    private Seat requireSeat(Long seatId) {
        Seat seat = seatMapper.selectById(seatId);
        if (seat == null) {
            throw new IllegalArgumentException("座位不存在：seatId=" + seatId);
        }
        return seat;
    }

    private String generateNo() {
        return "APT" + LocalDateTime.now().format(NO_TIME)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase();
    }

    private AppointmentVO toVO(CheckinAppointment appt, Seat seat) {
        AppointmentVO vo = new AppointmentVO();
        BeanUtils.copyProperties(appt, vo);
        if (seat != null) {
            vo.setVenue(seat.getVenue());
            vo.setArea(seat.getArea());
            vo.setRowNo(seat.getRowNo());
            vo.setSeatNo(seat.getSeatNo());
        }
        return vo;
    }
}
