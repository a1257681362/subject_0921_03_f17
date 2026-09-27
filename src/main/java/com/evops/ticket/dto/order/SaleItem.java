package com.evops.ticket.dto.order;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class SaleItem {
    @NotNull(message = "座位ID不能为空")
    private Long seatId;

    /**
     * 票款金额，必填。空值（报文 {@code <price></price>} / 缺省）语义是"平台未提供金额（未知）"，
     * 不是 0——禁止落库按 0 参与票款汇总；真为零元票（赠票/免票）须显式传 0。
     */
    @NotNull(message = "票款金额不能为空：空值表示金额未知，须与代理平台核实后补报，不得按 0 入账")
    @DecimalMin(value = "0.00", message = "票款金额不能为负数")
    private BigDecimal price;
}
