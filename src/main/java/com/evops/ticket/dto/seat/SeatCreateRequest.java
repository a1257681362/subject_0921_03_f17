package com.evops.ticket.dto.seat;

import com.evops.ticket.dto.common.BizTimeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;

@Data
@EqualsAndHashCode(callSuper = true)
public class SeatCreateRequest extends BizTimeRequest {
    @NotBlank(message = "场馆不能为空")
    private String venue;

    @NotBlank(message = "区域不能为空")
    private String area;

    @NotBlank(message = "排不能为空")
    private String rowNo;

    @NotBlank(message = "座号不能为空")
    private String seatNo;

    private String remark;
}
