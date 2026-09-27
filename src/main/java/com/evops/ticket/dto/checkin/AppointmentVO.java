package com.evops.ticket.dto.checkin;

import com.evops.ticket.mask.MaskType;
import com.evops.ticket.mask.annotation.Sensitive;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 检票排期预约视图。
 *
 * <p>观众姓名/联系方式为敏感字段：出系统（接口返回、单据导出、幂等快照回放）统一
 * 在序列化期按当前生效规则打码；白名单场务角色可见完整值并逐字段留痕。
 * 预约号作为留痕定位的业务主键。
 */
@Data
public class AppointmentVO {
    private Long id;
    private String appointmentNo;
    private Long seatId;
    private String venue;
    private String area;
    private String rowNo;
    private String seatNo;

    /** 观众姓名：非白名单角色看到 周* 样式。 */
    @Sensitive(value = MaskType.NAME, bizType = "CheckinAppointment", bizIdField = "appointmentNo")
    private String visitorName;

    /** 联系方式：非白名单角色看到 138****8000 样式。 */
    @Sensitive(value = MaskType.CONTACT, bizType = "CheckinAppointment", bizIdField = "appointmentNo")
    private String contact;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String status;
    private String cancelReason;
    private String operator;
    private String requestId;
    private LocalDateTime bizTime;
    private Integer version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
