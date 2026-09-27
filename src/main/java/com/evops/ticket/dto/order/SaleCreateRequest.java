package com.evops.ticket.dto.order;

import com.evops.ticket.dto.common.BizTimeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SaleCreateRequest extends BizTimeRequest {
    @NotNull(message = "场次ID不能为空")
    private Long sessionId;

    @NotEmpty(message = "售票明细不能为空")
    @Valid
    private List<SaleItem> items;
}
