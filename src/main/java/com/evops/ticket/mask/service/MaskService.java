package com.evops.ticket.mask.service;

import com.evops.ticket.mask.EffectiveRule;
import com.evops.ticket.mask.MaskContext;
import com.evops.ticket.mask.MaskType;
import com.evops.ticket.mask.entity.SensitiveViewLog;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 脱敏统一口径：接口 JSON 序列化与单据导出都只走这一处判定，保证两边口径一致。
 *
 * <p>判定顺序：
 * <ol>
 *   <li>字段打码类型：当前规则版本配置优先，缺省回退字段注解声明；</li>
 *   <li>放行：存在请求脱敏上下文 且 当前启用岗位并集与该版本白名单有交集 → 原样返回，
 *       同时收集一条明文查看留痕（留痕记录实际命中白名单的岗位；请求结束统一刷库）；</li>
 *   <li>其余一律打码：无登录态、所有岗位都不在白名单、规则缺失（失败关闭）均不放出明文。</li>
 * </ol>
 */
@Service
public class MaskService {

    private final MaskRuleService ruleService;

    public MaskService(MaskRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * @param fieldName   字段名（规则表按名覆盖打码类型）
     * @param defaultType 注解声明的默认打码类型
     * @param bizType     业务数据类型（留痕）
     * @param bizId       业务主键/业务号（留痕定位，可空）
     * @param raw         原始值
     * @return 出系统值：白名单内为原文，其余为打码值
     */
    public String evaluate(String fieldName, MaskType defaultType, String bizType,
                           String bizId, String raw) {
        if (raw == null) {
            return null;
        }
        EffectiveRule rule = ruleService.current();
        MaskType type = rule.fieldType(fieldName);
        if (type == null) {
            type = defaultType;
        }

        MaskContext ctx = MaskContext.get();
        // 一人多岗：启用岗位并集与白名单有交集即放行；留痕记下实际命中的岗位
        String allowedRole = ctx == null ? null : rule.firstAllowedRole(ctx.getRoleCodes());
        if (allowedRole != null) {
            recordPlainView(ctx, allowedRole, rule.getRuleVersion(), bizType, bizId, fieldName, type);
            return raw;
        }
        return type.mask(raw);
    }

    /** 规则版本当前收录的敏感字段名（供 Map 回放路径识别快照里的敏感键）。 */
    public java.util.Set<String> sensitiveFieldNames() {
        return ruleService.current().fieldNames();
    }

    /** 当前生效规则（Map 回放路径按字段名取配置的打码类型）。 */
    public EffectiveRule currentRule() {
        return ruleService.current();
    }

    private void recordPlainView(MaskContext ctx, String allowedRole, int ruleVersion,
                                 String bizType, String bizId, String fieldName, MaskType type) {
        SensitiveViewLog viewLog = new SensitiveViewLog();
        viewLog.setUserId(ctx.getUserId());
        viewLog.setUsername(ctx.getUsername());
        viewLog.setRoleCode(allowedRole);
        viewLog.setBizType(bizType);
        viewLog.setBizId(bizId);
        viewLog.setFieldName(fieldName);
        viewLog.setMaskType(type.name());
        viewLog.setRuleVersion(ruleVersion);
        viewLog.setChannel(ctx.getChannel());
        viewLog.setRequestPath(ctx.getRequestPath());
        viewLog.setRequestId(ctx.getRequestId());
        viewLog.setViewTime(LocalDateTime.now());
        ctx.addViewLog(viewLog);
    }
}
