package com.evops.ticket.dto.checkin;

import com.evops.ticket.dto.common.BizTimeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 检票排期预约请求：按半开区间 [startTime, endTime) 预约一个座位的检票档口。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AppointmentCreateRequest extends BizTimeRequest {

    @NotNull(message = "座位ID不能为空")
    private Long seatId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /** 预约人/观众（可选，供场务核对） */
    private String visitorName;

    /** 联系方式（可选） */
    private String contact;
}
