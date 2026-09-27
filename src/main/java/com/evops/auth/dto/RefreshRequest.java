package com.evops.auth.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class RefreshRequest {
    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
