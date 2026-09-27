package com.evops.ticket.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.checkin.AppointmentCreateRequest;
import com.evops.ticket.dto.checkin.AppointmentVO;
import com.evops.ticket.entity.CheckinAppointment;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.enums.AppointmentStatus;
import com.evops.ticket.mapper.CheckinAppointmentMapper;
import com.evops.ticket.mapper.SeatMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消即释放回归：取消提交后原时段必须立即可再约，不得有"等几分钟才放出来"的窗口。
 *
 * 覆盖：
 *  - 缺陷本身：取消后立即同区间再约、与取消行重叠的再约；
 *  - 相邻路径：未取消的重叠/20 分钟缓冲冲突、缓冲边界等值、重复取消、
 *    空档推荐不再计入已取消行；
 *  - 跨天：跨午夜预约取消后释放、跨午夜相邻缓冲冲突；
 *  - 并发：同座位双约恰好一成一败；取消与再约并发，取消先提交后再约必成。
 */
@SpringBootTest
class CheckinAppointmentReleaseTest {

    @Autowired private CheckinAppointmentService appointmentService;
    @Autowired private CheckinAppointmentMapper appointmentMapper;
    @Autowired private SeatMapper seatMapper;
    @Autowired private IdempotentService idempotentService;

    @MockBean private com.evops.ticket.agent.service.AgentMessageRetryJob agentRetryJob;
    @MockBean private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;

    private final List<Long> seatIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Context(
                "ticket_check01", "release-test-" + System.nanoTime(), "/test", 0L));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ==================== 缺陷本身：取消即释放 ====================

    @Test
    void cancel_thenRebookSameSlot_immediatelySucceeds() {
        Long seat = newSeat("R-CANCEL-1");
        LocalDateTime s = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime e = s.plusMinutes(30);

        Long first = create(seat, s, e);
        appointmentService.cancel(first, "行程变更", null);
        assertEquals(AppointmentStatus.CANCELLED.name(), appointmentMapper.selectById(first).getStatus());

        // 取消提交后，同一时段必须立即可以再约
        Long second = create(seat, s, e);
        assertNotNull(second);
        assertEquals(AppointmentStatus.ACTIVE.name(), appointmentMapper.selectById(second).getStatus());
        // 历史行保留：两行同区间并存，仅一行 ACTIVE
        List<CheckinAppointment> rows = rowsAt(seat, s, e);
        assertEquals(2, rows.size());
        assertEquals(1, rows.stream().filter(r -> AppointmentStatus.ACTIVE.name().equals(r.getStatus())).count());
    }

    @Test
    void cancel_thenOverlappingRebook_succeedsCancelledRowIgnored() {
        LocalDateTime s = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime e = s.plusMinutes(30);
        // 与已取消区间头部重叠的新约（取消行不参与冲突判定）
        Long seatA = newSeat("R-CANCEL-2A");
        Long firstA = create(seatA, s, e);
        appointmentService.cancel(firstA, null, null);
        assertNotNull(create(seatA, s.minusMinutes(10), s.plusMinutes(10)));

        // 与已取消区间尾部重叠的新约，换独立座位避免与上一条新约撞缓冲
        Long seatB = newSeat("R-CANCEL-2B");
        Long firstB = create(seatB, s, e);
        appointmentService.cancel(firstB, null, null);
        assertNotNull(create(seatB, e.minusMinutes(5), e.plusMinutes(25)));
    }

    @Test
    void cancel_freesSlotInSuggestAndOccupancy_views() {
        Long seat = newSeat("R-CANCEL-3");
        LocalDateTime s = LocalDateTime.of(2026, 10, 1, 10, 0);
        LocalDateTime e = s.plusMinutes(30);
        Long first = create(seat, s, e);

        // 取消前：占用查询能看到、空档推荐排除该段
        assertEquals(1, appointmentService.occupancy(seat, s.minusHours(1), e.plusHours(1)).getAppointments().size());
        appointmentService.cancel(first, null, null);
        assertEquals(0, appointmentService.occupancy(seat, s.minusHours(1), e.plusHours(1)).getAppointments().size());

        // 取消后：30 分钟空档应能在原时段推荐出来
        boolean coversOriginal = appointmentService
                .suggestFreeSlots(seat, s, e, 30).stream()
                .anyMatch(slot -> !slot.getStartTime().isAfter(s) && !slot.getEndTime().isBefore(e));
        assertTrue(coversOriginal, "取消后原时段应出现在空档推荐中");
    }

    // ==================== 控制器同款幂等事务链路 ====================

    /**
     * 走与控制器一致的路径：每个写请求都包在 IdempotentService.execute 的幂等事务里。
     * 验证该事务嵌套下"取消提交 -> 立即再约"仍然立即可见，不因幂等包装而延后释放。
     */
    @Test
    void cancelThenRebook_throughIdempotentWrapper_releasesImmediately() {
        Long seat = newSeat("R-IDEM-1");
        LocalDateTime s = LocalDateTime.of(2026, 12, 1, 10, 0);
        LocalDateTime e = s.plusMinutes(30);

        AppointmentCreateRequest createReq = buildReq(seat, s, e);
        freshRequest();
        Object created = idempotentService.execute(idempotentService.hash(createReq), () -> {
            AppointmentVO vo = appointmentService.create(createReq);
            return IdempotentResult.of("CHECKIN_APPT:" + vo.getId(), vo);
        });
        Long first = ((AppointmentVO) created).getId();

        freshRequest();
        idempotentService.execute(idempotentService.hash(null), () -> {
            AppointmentVO vo = appointmentService.cancel(first, "改期", null);
            return IdempotentResult.of("CHECKIN_APPT:" + first + ":CANCEL", vo);
        });

        AppointmentCreateRequest rebookReq = buildReq(seat, s, e);
        freshRequest();
        Object rebooked = idempotentService.execute(idempotentService.hash(rebookReq), () -> {
            AppointmentVO vo = appointmentService.create(rebookReq);
            return IdempotentResult.of("CHECKIN_APPT:" + vo.getId(), vo);
        });
        assertNotNull(((AppointmentVO) rebooked).getId(), "幂等事务链路下取消后同时段必须立即可再约");
    }

    private AppointmentCreateRequest buildReq(Long seat, LocalDateTime s, LocalDateTime e) {
        AppointmentCreateRequest req = new AppointmentCreateRequest();
        req.setSeatId(seat);
        req.setStartTime(s);
        req.setEndTime(e);
        req.setVisitorName("访客");
        req.setContact("13800000000");
        return req;
    }

    /** 模拟每个 HTTP 请求由拦截器注入新的 X-Request-Id。 */
    private void freshRequest() {
        RequestContext.set(new RequestContext.Context(
                "ticket_check01", "idem-" + System.nanoTime(), "/api/ticket/checkin/appointments", 0L));
    }

    // ==================== 相邻路径：冲突与缓冲 ====================

    @Test
    void activeAppointment_overlap_conflicts() {
        Long seat = newSeat("R-CONF-1");
        LocalDateTime s = LocalDateTime.of(2026, 10, 2, 10, 0);
        create(seat, s, s.plusMinutes(30));
        assertThrows(AppointmentConflictException.class,
                () -> create(seat, s, s.plusMinutes(30)));
        assertThrows(AppointmentConflictException.class,
                () -> create(seat, s.minusMinutes(10), s.plusMinutes(10)));
    }

    @Test
    void activeAppointment_buffer20m_boundary() {
        Long seat = newSeat("R-CONF-2");
        LocalDateTime s = LocalDateTime.of(2026, 10, 2, 10, 0);
        LocalDateTime e = s.plusMinutes(30);
        create(seat, s, e);

        // 首尾间隔不足 20 分钟：19 分钟冲突
        assertThrows(AppointmentConflictException.class,
                () -> create(seat, e.plusMinutes(19), e.plusMinutes(49)));
        // 恰好 20 分钟：不冲突
        assertNotNull(create(seat, e.plusMinutes(20), e.plusMinutes(50)));
        // 前向缓冲同样 20 分钟：19 分钟冲突
        assertThrows(AppointmentConflictException.class,
                () -> create(seat, s.minusMinutes(49), s.minusMinutes(19)));
        assertNotNull(create(seat, s.minusMinutes(50), s.minusMinutes(20)));
    }

    @Test
    void cancel_twice_secondRejected_andRebookStillWorks() {
        Long seat = newSeat("R-CANCEL-DUP");
        LocalDateTime s = LocalDateTime.of(2026, 10, 2, 14, 0);
        LocalDateTime e = s.plusMinutes(30);
        Long first = create(seat, s, e);
        appointmentService.cancel(first, null, null);
        assertThrows(IllegalArgumentException.class, () -> appointmentService.cancel(first, null, null));
        // 重复取消被拒不影响释放结论
        assertNotNull(create(seat, s, e));
    }

    @Test
    void cancel_nonexistent_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> appointmentService.cancel(Long.MAX_VALUE, null, null));
    }

    // ==================== 跨天场景 ====================

    @Test
    void crossMidnight_cancel_thenRebookImmediately_succeeds() {
        Long seat = newSeat("R-XDAY-1");
        // 跨午夜预约 D1 23:50 - D2 00:20
        LocalDateTime s = LocalDateTime.of(2026, 10, 3, 23, 50);
        LocalDateTime e = LocalDateTime.of(2026, 10, 4, 0, 20);
        Long first = create(seat, s, e);
        appointmentService.cancel(first, null, null);

        // 取消后立即在跨午夜的同一区间、以及 D2 00:00 附近再约均成功
        assertNotNull(create(seat, s, e));
    }

    @Test
    void crossMidnight_activeAppointment_bufferAcrossDays_conflicts() {
        Long seat = newSeat("R-XDAY-2");
        // D1 23:50 - D2 00:10 的有效预约
        LocalDateTime s = LocalDateTime.of(2026, 10, 5, 23, 50);
        LocalDateTime e = LocalDateTime.of(2026, 10, 6, 0, 10);
        create(seat, s, e);

        // D2 00:25 开始：与前一段结束(00:10)仅隔 15 分钟 -> 跨天缓冲冲突
        assertThrows(AppointmentConflictException.class,
                () -> create(seat, LocalDateTime.of(2026, 10, 6, 0, 25),
                        LocalDateTime.of(2026, 10, 6, 0, 55)));
        // D2 00:30 开始：恰好 20 分钟 -> 可约
        assertNotNull(create(seat, LocalDateTime.of(2026, 10, 6, 0, 30),
                LocalDateTime.of(2026, 10, 6, 1, 0)));

        Long seat2 = newSeat("R-XDAY-3");
        // D2 00:05 - 00:35 的有效预约；D1 23:50 开始的预约与之间隔不足缓冲
        LocalDateTime s2 = LocalDateTime.of(2026, 10, 8, 0, 5);
        LocalDateTime e2 = LocalDateTime.of(2026, 10, 8, 0, 35);
        create(seat2, s2, e2);
        assertThrows(AppointmentConflictException.class,
                () -> create(seat2, LocalDateTime.of(2026, 10, 7, 23, 50),
                        LocalDateTime.of(2026, 10, 8, 0, 0)));
    }

    // ==================== 并发 ====================

    @Test
    void concurrentDoubleBooking_exactlyOneWins() throws Exception {
        Long seat = newSeat("R-CONC-1");
        LocalDateTime s = LocalDateTime.of(2026, 10, 9, 10, 0);
        LocalDateTime e = s.plusMinutes(30);

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String op = "op-" + i;
            futures.add(pool.submit(() -> {
                bindContext();
                ready.countDown();
                start.await();
                try {
                    return create(seat, s, e, op);
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();

        int success = 0;
        int conflict = 0;
        for (Future<Object> f : futures) {
            Object r = f.get(10, TimeUnit.SECONDS);
            if (r instanceof Long) {
                success++;
            } else if (r instanceof AppointmentConflictException) {
                conflict++;
            } else {
                throw new AssertionError("并发预约出现非预期结果: " + r, r instanceof Throwable ? (Throwable) r : null);
            }
        }
        pool.shutdown();
        assertEquals(1, success, "同一时段并发双约必须恰好一条成功");
        assertEquals(n - 1, conflict);
    }

    @Test
    void concurrentCancelAndRebook_afterCancelCommits_rebookSucceeds() throws Exception {
        Long seat = newSeat("R-CONC-2");
        LocalDateTime s = LocalDateTime.of(2026, 10, 10, 10, 0);
        LocalDateTime e = s.plusMinutes(30);
        Long first = create(seat, s, e);

        // 多轮并发：每轮一个取消者、多个再约者；取消提交后最终必须能再约成功
        for (int round = 0; round < 5; round++) {
            final Long active = create(seat, s.plusDays(20L + round), e.plusDays(20L + round));
            final LocalDateTime rs = s.plusDays(20L + round);
            final LocalDateTime re = e.plusDays(20L + round);
            int bookers = 4;
            ExecutorService pool = Executors.newFixedThreadPool(bookers + 1);
            CountDownLatch ready = new CountDownLatch(bookers + 1);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> cancelError = new AtomicReference<>();

            Future<?> cancelF = pool.submit(() -> {
                bindContext();
                ready.countDown();
                try {
                    start.await();
                    appointmentService.cancel(active, "并发取消", null);
                } catch (Throwable t) {
                    cancelError.set(t);
                }
            });
            List<Future<Object>> bookFutures = new ArrayList<>();
            for (int i = 0; i < bookers; i++) {
                final String op = "cb-" + round + "-" + i;
                bookFutures.add(pool.submit(() -> {
                    bindContext();
                    ready.countDown();
                    start.await();
                    try {
                        return create(seat, rs, re, op);
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            cancelF.get(10, TimeUnit.SECONDS);

            // 再约者可能在取消前先拿到锁而冲突；取消提交后补一次再约，必须成功——
            // 这正是"取消提交后可立即再约"的保证，不允许出现残留占用
            boolean booked = false;
            for (Future<Object> f : bookFutures) {
                Object r = f.get(10, TimeUnit.SECONDS);
                if (r instanceof Long) {
                    booked = true;
                } else if (!(r instanceof AppointmentConflictException)) {
                    throw new AssertionError("并发再约出现非预期结果: " + r,
                            r instanceof Throwable ? (Throwable) r : null);
                }
            }
            pool.shutdown();
            if (!booked) {
                assertNotNull(create(seat, rs, re),
                        "取消已提交，原时段再约不允许再报占用");
            }
        }
    }

    // ==================== 辅助 ====================

    private Long create(Long seat, LocalDateTime s, LocalDateTime e) {
        return create(seat, s, e, "张三");
    }

    private Long create(Long seat, LocalDateTime s, LocalDateTime e, String operator) {
        RequestContext.set(new RequestContext.Context(operator, "rel-" + System.nanoTime(), "/test", 0L));
        AppointmentCreateRequest req = new AppointmentCreateRequest();
        req.setSeatId(seat);
        req.setStartTime(s);
        req.setEndTime(e);
        req.setVisitorName("访客" + operator);
        req.setContact("13800000000");
        return appointmentService.create(req).getId();
    }

    private void bindContext() {
        RequestContext.set(new RequestContext.Context(
                "worker-" + Thread.currentThread().getId(),
                "rel-" + Thread.currentThread().getId() + "-" + System.nanoTime(), "/test", 0L));
    }

    private List<CheckinAppointment> rowsAt(Long seat, LocalDateTime s, LocalDateTime e) {
        return appointmentMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CheckinAppointment>()
                .eq(CheckinAppointment::getSeatId, seat)
                .eq(CheckinAppointment::getStartTime, s)
                .eq(CheckinAppointment::getEndTime, e));
    }

    private Long newSeat(String code) {
        Seat seat = new Seat();
        seat.setVenue("释放测试场馆");
        seat.setArea("池座");
        seat.setRowNo("Q");
        seat.setSeatNo(code);
        seat.setOperator("tester");
        seatMapper.insert(seat);
        seatIds.add(seat.getId());
        return seat.getId();
    }
}
