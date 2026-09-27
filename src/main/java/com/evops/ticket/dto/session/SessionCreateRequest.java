package com.evops.ticket.dto.session;

import com.evops.ticket.dto.common.BizTimeRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class SessionCreateRequest extends BizTimeRequest {
    @NotBlank(message = "演出名称不能为空")
    private String showName;

    @NotBlank(message = "场次短码不能为空")
    @Pattern(regexp = "^[A-Z0-9]{2,8}$", message = "场次短码需为2-8位大写字母或数字")
    private String sessionCode;

    @NotBlank(message = "场馆不能为空")
    private String venue;

    private LocalDateTime showTime;

    private String remark;
}
