package com.evops.ticket.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 票务业务表公共字段：操作人、请求号、业务时间、数据版本、逻辑删除标记。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class TicketBaseEntity extends BaseEntity {
    /** 操作人（员工号字符串，缺省 system） */
    private String operator;

    /** 请求号（X-Request-Id） */
    private String requestId;

    /** 业务时间（请求体 bizTime，缺省服务器时间） */
    private LocalDateTime bizTime;

    /** 数据版本（乐观锁） */
    @Version
    private Integer version;

    /** 逻辑删除：0=有效；删除时写本行 id，保证删除后可按同业务键重建 */
    @TableLogic(value = "0", delval = "id")
    private Long deleted;
}
