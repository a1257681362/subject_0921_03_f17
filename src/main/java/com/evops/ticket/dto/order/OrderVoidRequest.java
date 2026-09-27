package com.evops.ticket.dto.order;

import com.evops.ticket.dto.common.BizTimeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class OrderVoidRequest extends BizTimeRequest {
    private String reason;
}
