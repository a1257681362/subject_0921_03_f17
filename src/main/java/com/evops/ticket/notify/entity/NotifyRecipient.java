package com.evops.ticket.notify.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.ticket.entity.TicketBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 场务收件人。staffNo 与后台登录名一致（站内信按工号投递），
 * email 用于邮件通道；两者至少有一个，缺哪个通道就跳过该通道并在记录里标 SKIPPED。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_notify_recipient")
public class NotifyRecipient extends TicketBaseEntity {

    private String recipientName;
    /** 工号（后台登录名）；仅有邮箱的外部协作人员可空 */
    private String staffNo;
    private String email;
    /** 岗位角色码，默认 STAGE_STAFF 场务 */
    private String roleCode;
    /** 1 启用 / 0 停用 */
    private Integer enabled;
    /** ACTIVE / DISABLED */
    private String status;
    private String remark;
}
