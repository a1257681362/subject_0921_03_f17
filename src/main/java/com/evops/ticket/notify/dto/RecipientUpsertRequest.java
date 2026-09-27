package com.evops.ticket.notify.dto;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

/**
 * 新建/修改场务收件人。工号与邮箱至少一个（站内信按工号、邮件按邮箱）。
 */
public class RecipientUpsertRequest {

    @NotBlank(message = "收件人姓名不能为空")
    @Size(max = 64)
    private String recipientName;

    /** 工号（后台登录名），外部协作人员可空 */
    @Size(max = 64)
    private String staffNo;

    @Email(message = "邮箱格式不正确")
    @Size(max = 128)
    private String email;

    @Size(max = 48)
    private String roleCode = "STAGE_STAFF";

    private Boolean enabled = Boolean.TRUE;

    @Size(max = 255)
    private String remark;

    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getStaffNo() { return staffNo; }
    public void setStaffNo(String staffNo) { this.staffNo = staffNo; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRoleCode() { return roleCode; }
    public void setRoleCode(String roleCode) { this.roleCode = roleCode; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
