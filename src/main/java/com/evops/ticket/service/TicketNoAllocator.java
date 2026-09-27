package com.evops.ticket.service;

import com.evops.ticket.entity.ShowSession;
import com.evops.ticket.entity.TicketNoAlloc;
import com.evops.ticket.mapper.TicketNoAllocMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 票号分配：T + 场次短码 + 10 位顺序号，如 TSH0010000000012。
 * 每场次一行计数，SELECT ... FOR UPDATE 串行化同场次分配，票号 UK 最终兜底。
 */
@Component
public class TicketNoAllocator {

    private final TicketNoAllocMapper allocMapper;

    public TicketNoAllocator(TicketNoAllocMapper allocMapper) {
        this.allocMapper = allocMapper;
    }

    public List<String> allocate(ShowSession session, int count) {
        Long sessionId = session.getId();
        TicketNoAlloc row = new TicketNoAlloc();
        row.setSessionId(sessionId);
        row.setCurVal(0L);
        try {
            allocMapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // H2 语句级回滚，计数行已存在时继续走加锁分配即可
        }

        TicketNoAlloc locked = allocMapper.selectForUpdate(sessionId);
        long start = locked.getCurVal();
        long next = start + count;
        locked.setCurVal(next);
        locked.setUpdateTime(LocalDateTime.now());
        allocMapper.updateById(locked);

        List<String> ticketNos = new ArrayList<>(count);
        for (long i = start + 1; i <= next; i++) {
            ticketNos.add("T" + session.getSessionCode() + String.format("%010d", i));
        }
        return ticketNos;
    }
}
