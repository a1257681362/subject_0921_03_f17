package com.evops.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.session.SessionCreateRequest;
import com.evops.ticket.dto.session.SessionVO;
import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.mapper.ShowSessionMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
public class ShowSessionService {

    private final ShowSessionMapper sessionMapper;

    public ShowSessionService(ShowSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    public SessionVO create(SessionCreateRequest req) {
        ShowSession session = new ShowSession();
        BeanUtils.copyProperties(req, session);
        session.setStatus("OPEN");
        session.setOperator(RequestContext.operator());
        session.setRequestId(RequestContext.requestId());
        session.setBizTime(req.getBizTime() != null ? req.getBizTime() : LocalDateTime.now());
        try {
            sessionMapper.insert(session);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("场次短码已存在");
        }
        return toVO(session);
    }

    public ShowSession requireExists(Long id) {
        ShowSession session = sessionMapper.selectById(id);
        if (session == null) {
            throw new IllegalArgumentException("场次不存在");
        }
        return session;
    }

    public SessionVO get(Long id) {
        return toVO(requireExists(id));
    }

    public PageVO<SessionVO> page(long page, long size, String venue) {
        LambdaQueryWrapper<ShowSession> wrapper = new LambdaQueryWrapper<>();
        if (venue != null && !venue.trim().isEmpty()) {
            wrapper.eq(ShowSession::getVenue, venue);
        }
        wrapper.orderByAsc(ShowSession::getId);
        Page<ShowSession> result = sessionMapper.selectPage(new Page<>(page, size), wrapper);
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getRecords().stream().map(this::toVO).collect(Collectors.toList()));
    }

    private SessionVO toVO(ShowSession session) {
        SessionVO vo = new SessionVO();
        BeanUtils.copyProperties(session, vo);
        return vo;
    }
}
