package com.evops.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.seat.SeatCreateRequest;
import com.evops.ticket.dto.seat.SeatVO;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.mapper.SeatMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class SeatService {

    private final SeatMapper seatMapper;

    public SeatService(SeatMapper seatMapper) {
        this.seatMapper = seatMapper;
    }

    public SeatVO create(SeatCreateRequest req) {
        Seat seat = new Seat();
        BeanUtils.copyProperties(req, seat);
        fillAudit(seat, req.getBizTime());
        try {
            seatMapper.insert(seat);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("座位已存在");
        }
        return toVO(seat);
    }

    public SeatVO get(Long id) {
        Seat seat = seatMapper.selectById(id);
        if (seat == null) {
            throw new IllegalArgumentException("座位不存在");
        }
        return toVO(seat);
    }

    public PageVO<SeatVO> page(long page, long size, String venue, String area) {
        LambdaQueryWrapper<Seat> wrapper = new LambdaQueryWrapper<>();
        if (venue != null && !venue.trim().isEmpty()) {
            wrapper.eq(Seat::getVenue, venue);
        }
        if (area != null && !area.trim().isEmpty()) {
            wrapper.eq(Seat::getArea, area);
        }
        wrapper.orderByAsc(Seat::getId);
        Page<Seat> result = seatMapper.selectPage(new Page<>(page, size), wrapper);
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::toVO)
                        .collect(java.util.stream.Collectors.toList()));
    }

    /** 逻辑删除：只打标记（deleted 写本行 id）。 */
    public boolean delete(Long id) {
        Seat seat = seatMapper.selectById(id);
        if (seat == null) {
            throw new IllegalArgumentException("座位不存在");
        }
        seat.setBizTime(LocalDateTime.now());
        int rows = seatMapper.deleteById(id);
        return rows > 0;
    }

    private void fillAudit(Seat seat, LocalDateTime bizTime) {
        seat.setOperator(RequestContext.operator());
        seat.setRequestId(RequestContext.requestId());
        seat.setBizTime(bizTime != null ? bizTime : LocalDateTime.now());
    }

    private SeatVO toVO(Seat seat) {
        SeatVO vo = new SeatVO();
        BeanUtils.copyProperties(seat, vo);
        return vo;
    }
}
