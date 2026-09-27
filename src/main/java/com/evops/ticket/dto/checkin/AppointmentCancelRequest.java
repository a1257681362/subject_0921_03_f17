package com.evops.ticket.dto.checkin;

import com.evops.ticket.dto.common.BizTimeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 取消预约：取消即释放，座位可立即再次预约。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class AppointmentCancelRequest extends BizTimeRequest {
    private String reason;
}
