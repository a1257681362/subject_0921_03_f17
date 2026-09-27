package com.evops.ticket.mask.service;

import com.evops.ticket.dto.checkin.AppointmentVO;
import com.evops.ticket.mask.MaskContext;
import com.evops.ticket.mask.entity.SensitiveViewLog;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 检票预约单据 CSV 导出（出系统）。
 *
 * <p>敏感列（观众姓名、联系方式）与接口走同一处 {@link MaskService} 判定，
 * 保证「导出与接口口径一致」：非白名单角色拿到打码值；白名单角色拿到完整值，
 * 且本次导出的出口通道记为 EXPORT 并逐字段留痕。
 *
 * <p>UTF-8 带 BOM，Excel 直开中文不乱码；流式写入响应输出流，整表不进内存
 * （行数据本身已由上限封顶）。
 */
@Service
public class AppointmentExportService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String BIZ_TYPE = "CheckinAppointment";

    private final MaskService maskService;

    public AppointmentExportService(MaskService maskService) {
        this.maskService = maskService;
    }

    public void writeCsv(List<AppointmentVO> rows, OutputStream out) throws IOException {
        MaskContext ctx = MaskContext.get();
        String previousChannel = ctx == null ? null : ctx.getChannel();
        if (ctx != null) {
            ctx.overrideChannel(SensitiveViewLog.CHANNEL_EXPORT);
        }
        try {
            // UTF-8 BOM（EF BB BF）：Excel 直开中文不乱码，在包裹 Writer 前直接写字节
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            OutputStreamWriter osw = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            BufferedWriter writer = new BufferedWriter(osw, 8192);
            writer.write("预约号,座位ID,场馆,区域,排,座号,观众姓名,联系方式,开始时间,结束时间,状态,操作人");
            writer.write("\r\n");
            for (AppointmentVO vo : rows) {
                writer.write(String.join(",",
                        csv(vo.getAppointmentNo()),
                        csv(String.valueOf(vo.getSeatId())),
                        csv(vo.getVenue()),
                        csv(vo.getArea()),
                        csv(vo.getRowNo()),
                        csv(vo.getSeatNo()),
                        csv(maskService.evaluate("visitorName", com.evops.ticket.mask.MaskType.NAME,
                                BIZ_TYPE, vo.getAppointmentNo(), vo.getVisitorName())),
                        csv(maskService.evaluate("contact", com.evops.ticket.mask.MaskType.CONTACT,
                                BIZ_TYPE, vo.getAppointmentNo(), vo.getContact())),
                        csv(time(vo.getStartTime())),
                        csv(time(vo.getEndTime())),
                        csv(vo.getStatus()),
                        csv(vo.getOperator())));
                writer.write("\r\n");
            }
            writer.flush();
        } finally {
            if (ctx != null) {
                ctx.overrideChannel(previousChannel);
            }
        }
    }

    private static String time(java.time.LocalDateTime t) {
        return t == null ? "" : FMT.format(t);
    }

    /** RFC 4180：含逗号/引号/换行的字段加双引号，内部引号双写。 */
    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
