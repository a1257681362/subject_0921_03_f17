package com.evops.ticket.notify.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

/**
 * 新建/修改通知模板。修改文案在服务端自动 tplVersion+1，调用方不传版本。
 */
public class TemplateUpsertRequest {

    @NotBlank(message = "模板编码不能为空")
    @Size(max = 48)
    private String templateCode;

    @NotBlank(message = "模板名称不能为空")
    @Size(max = 96)
    private String templateName;

    @NotBlank(message = "事件码不能为空")
    @Size(max = 48)
    private String eventCode;

    /** INBOX / EMAIL */
    @NotBlank(message = "通道不能为空")
    @Pattern(regexp = "INBOX|EMAIL", message = "通道仅支持 INBOX/EMAIL")
    private String channel;

    @NotBlank(message = "标题模板不能为空")
    @Size(max = 255)
    private String titleTemplate;

    @NotBlank(message = "正文模板不能为空")
    private String bodyTemplate;

    private Boolean enabled = Boolean.TRUE;

    @Size(max = 255)
    private String remark;

    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getEventCode() { return eventCode; }
    public void setEventCode(String eventCode) { this.eventCode = eventCode; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getTitleTemplate() { return titleTemplate; }
    public void setTitleTemplate(String titleTemplate) { this.titleTemplate = titleTemplate; }
    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
