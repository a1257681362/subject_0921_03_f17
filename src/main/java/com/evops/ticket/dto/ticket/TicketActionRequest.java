package com.evops.ticket.dto.ticket;

import com.evops.ticket.dto.common.BizTimeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class TicketActionRequest extends BizTimeRequest {
    private String reason;
}
