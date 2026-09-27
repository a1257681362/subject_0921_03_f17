package com.evops.ticket.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.dto.checkin.AppointmentCreateRequest;
import com.evops.ticket.entity.Seat;
import com.evops.ticket.enums.AppointmentStatus;
import com.evops.ticket.mapper.CheckinAppointmentMapper;
import com.evops.ticket.mapper.SeatMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 生产同款存储形态复核：H2 文件库、默认（非 MySQL）模式、AUTO_SERVER，
 * 验证"取消即释放"与"座位行锁串行"在该形态下与内存 MySQL 模式行为一致。
 * 线上缺陷（取消后时段迟迟不放）若由存储形态差异引起，应在此复现。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:file:./target/it-h2-filemode/evops;AUTO_SERVER=TRUE;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
class CheckinAppointmentReleaseFileModeTest {

    @Autowired private CheckinAppointmentService appointmentService;
    @Autowired private CheckinAppointmentMapper appointmentMapper;
    @Autowired private SeatMapper seatMapper;

    @MockBean private com.evops.ticket.agent.service.AgentMessageRetryJob agentRetryJob;
    @MockBean private com.evops.ticket.notify.service.NotifyRetryJob notifyRetryJob;

    @BeforeAll
    static void cleanFileDb() {
        // 清掉上次运行的文件库，保证 Flyway 从 V1 全新建库
        deleteDir(new File("./target/it-h2-filemode"));
    }

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Context(
                "ticket_check01", "filemode-" + System.nanoTime(), "/test", 0L));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void cancel_thenRebookSameSlot_immediatelySucceeds_fileMode() {
        Long seat = newSeat("FM-CANCEL-1");
        LocalDateTime s = LocalDateTime.of(2026, 11, 1, 10, 0);
        LocalDateTime e = s.plusMinutes(30);
        Long first = create(seat, s, e);
        appointmentService.cancel(first, "行程变更", null);
        assertEquals(AppointmentStatus.CANCELLED.name(), appointmentMapper.selectById(first).getStatus());
        assertNotNull(create(seat, s, e), "文件库形态下取消后同时段必须立即可再约");
    }

    @Test
    void concurrentDoubleBooking_exactlyOneWins_fileMode() throws Exception {
        Long seat = newSeat("FM-CONC-1");
        LocalDateTime s = LocalDateTime.of(2026, 11, 2, 10, 0);
        LocalDateTime e = s.plusMinutes(30);
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                RequestContext.set(new RequestContext.Context(
                        "fm-" + Thread.currentThread().getId(),
                        "fm-" + System.nanoTime(), "/test", 0L));
                ready.countDown();
                start.await();
                try {
                    return create(seat, s, e);
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        int success = 0;
        for (Future<Object> f : futures) {
            if (f.get(15, TimeUnit.SECONDS) instanceof Long) {
                success++;
            }
        }
        pool.shutdown();
        assertEquals(1, success, "文件库形态下座位行锁必须保证并发双约恰好一条成功");
    }

    private Long create(Long seat, LocalDateTime s, LocalDateTime e) {
        AppointmentCreateRequest req = new AppointmentCreateRequest();
        req.setSeatId(seat);
        req.setStartTime(s);
        req.setEndTime(e);
        req.setVisitorName("访客");
        req.setContact("13800000000");
        return appointmentService.create(req).getId();
    }

    private Long newSeat(String code) {
        Seat seat = new Seat();
        seat.setVenue("文件库复核场馆");
        seat.setArea("池座");
        seat.setRowNo("F");
        seat.setSeatNo(code);
        seat.setOperator("tester");
        seatMapper.insert(seat);
        return seat.getId();
    }

    private static void deleteDir(File dir) {
        if (!dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDir(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
