package com.evops.ticket.controller;

import com.evops.common.ApiResponse;
import com.evops.common.web.PageVO;
import com.evops.rbac.annotation.RequirePermission;
import com.evops.ticket.dto.checkin.AppointmentCancelRequest;
import com.evops.ticket.dto.checkin.AppointmentCreateRequest;
import com.evops.ticket.dto.checkin.AppointmentVO;
import com.evops.ticket.dto.checkin.FreeSlotVO;
import com.evops.ticket.dto.checkin.SeatOccupancyVO;
import com.evops.ticket.mask.config.MaskProperties;
import com.evops.ticket.mask.service.AppointmentExportService;
import com.evops.ticket.service.CheckinAppointmentService;
import com.evops.ticket.service.IdempotentResult;
import com.evops.ticket.service.IdempotentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.IOException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 检票排期（checkin-1）。仅新增端点，不改动既有票务对外契约。
 * 冲突不走 HTTP 错误码：200 + success=false，data 为冲突详情与推荐空档。
 */
@RestController
@RequestMapping("/api/ticket/checkin")
public class CheckinAppointmentController {

    private final CheckinAppointmentService appointmentService;
    private final IdempotentService idempotentService;
    private final AppointmentExportService exportService;
    private final MaskProperties maskProperties;

    private static final DateTimeFormatter EXPORT_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public CheckinAppointmentController(CheckinAppointmentService appointmentService,
                                        IdempotentService idempotentService,
                                        AppointmentExportService exportService,
                                        MaskProperties maskProperties) {
        this.appointmentService = appointmentService;
        this.idempotentService = idempotentService;
        this.exportService = exportService;
        this.maskProperties = maskProperties;
    }

    /** 预约检票档口：区间重叠或与相邻预约间隔不足 20 分钟返回冲突详情与推荐空档。 */
    @PostMapping("/appointments")
    @RequirePermission("checkin:appointment:create")
    public ApiResponse<Object> create(@Valid @RequestBody AppointmentCreateRequest request) {
        Object data = idempotentService.execute(idempotentService.hash(request), () -> {
            AppointmentVO vo = appointmentService.create(request);
            return IdempotentResult.of("CHECKIN_APPT:" + vo.getId(), vo);
        });
        return ApiResponse.ok(data);
    }

    /** 取消即释放：提交成功后该座位可立即再约。 */
    @PostMapping("/appointments/{id}/cancel")
    @RequirePermission("checkin:appointment:cancel")
    public ApiResponse<Object> cancel(@PathVariable Long id,
                                      @RequestBody(required = false) AppointmentCancelRequest request) {
        AppointmentCancelRequest body = request != null ? request : new AppointmentCancelRequest();
        Object data = idempotentService.execute(idempotentService.hash(body), () -> {
            AppointmentVO vo = appointmentService.cancel(id, body.getReason(), body.getBizTime());
            return IdempotentResult.of("CHECKIN_APPT:" + id + ":CANCEL", vo);
        });
        return ApiResponse.ok(data);
    }

    @GetMapping("/appointments/{id}")
    @RequirePermission("checkin:appointment:view")
    public ApiResponse<AppointmentVO> get(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.get(id));
    }

    /**
     * 检票预约单据导出（CSV，UTF-8 BOM，Excel 直开）。
     *
     * <p>敏感列（观众姓名/联系方式）与接口走同一处脱敏判定，导出口径与接口完全一致；
     * 白名单角色导出明文按 EXPORT 通道逐字段留痕。单次导出行数有硬上限（默认 5000，
     * 只能调小），超限在打开响应流之前拒绝，回 200+success=false JSON，绝不产出半截文件。
     */
    @GetMapping("/appointments/export")
    @RequirePermission("checkin:appointment:view")
    public void exportAppointments(
            @RequestParam(required = false) Long seatId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            HttpServletResponse response) throws IOException {
        int maxRows = maskProperties.effectiveExportMaxRows();
        long total = appointmentService.countForExport(seatId, status, from, to);
        if (total > maxRows) {
            // 响应尚未提交：抛业务异常由全局处理器回 JSON，不写半截 CSV
            throw new IllegalArgumentException("导出预约单据 " + total + " 行，超过单次上限 " + maxRows
                    + " 行，请缩小时间范围或增加座位/状态过滤后分批导出");
        }
        List<AppointmentVO> rows = appointmentService.listForExport(seatId, status, from, to, maxRows);

        String fileName = "检票预约单据_" + LocalDateTime.now().format(EXPORT_TS) + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        String encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"checkin-appointments.csv\"; filename*=UTF-8''" + encoded);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        exportService.writeCsv(rows, response.getOutputStream());
    }

    /** 预约明细分页：可按座位 / 状态 / 时间区间过滤。时间格式 ISO，如 2026-09-23T10:00:00。 */
    @GetMapping("/appointments")
    @RequirePermission("checkin:appointment:view")
    public ApiResponse<PageVO<AppointmentVO>> page(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long seatId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ApiResponse.ok(appointmentService.page(page, size, seatId, status, from, to));
    }

    /** 查询某座位在给定区间内的有效占用。 */
    @GetMapping("/seats/{seatId}/occupancy")
    @RequirePermission("checkin:seat:view")
    public ApiResponse<SeatOccupancyVO> occupancy(
            @PathVariable Long seatId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ApiResponse.ok(appointmentService.occupancy(seatId, start, end));
    }

    /**
     * 空档推荐：返回目标座位在目标时段所在自然日起 3 天内、可容纳时长的就近空档。
     * durationMinutes 缺省取 wantedEnd-wantedStart。
     */
    @GetMapping("/seats/{seatId}/free-slots")
    @RequirePermission("checkin:seat:view")
    public ApiResponse<List<FreeSlotVO>> freeSlots(
            @PathVariable Long seatId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime wantedStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime wantedEnd,
            @RequestParam(required = false) Integer durationMinutes) {
        return ApiResponse.ok(appointmentService.suggestFreeSlots(seatId, wantedStart, wantedEnd, durationMinutes));
    }
}
