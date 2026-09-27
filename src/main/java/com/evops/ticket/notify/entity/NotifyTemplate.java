package com.evops.ticket.notify.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.ticket.entity.TicketBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通知模板。按 事件码 + 通道 配置；修改文案 {@code tplVersion+1}（列 tpl_version，
 * 与 TicketBaseEntity 的乐观锁 version 区分），发送时把当时版本号与渲染结果快照进
 * 发送记录，模板后续改动不影响已发内容。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_notify_template")
public class NotifyTemplate extends TicketBaseEntity {

    /** 模板业务编码（同事件下便于识别，如 CHECK_STAGE） */
    private String templateCode;
    private String templateName;
    /** 触发事件码：TICKET_CHECK ... */
    private String eventCode;
    /** INBOX / EMAIL */
    private String channel;
    /** 标题模板（支持 ${var} 占位） */
    private String titleTemplate;
    /** 正文模板（支持 ${var} 占位） */
    private String bodyTemplate;
    /** 1 启用 / 0 停用 */
    private Integer enabled;
    /** 文案版本：每次修改 +1（独立列，乐观锁版本走基类 version） */
    @TableField("tpl_version")
    private Integer templateVersion;
    /** ACTIVE / DISABLED */
    private String status;
    private String remark;
}
