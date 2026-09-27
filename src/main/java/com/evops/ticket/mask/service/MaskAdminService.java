package com.evops.ticket.mask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.ticket.mask.MaskType;
import com.evops.ticket.mask.dto.FieldRuleItem;
import com.evops.ticket.mask.dto.MaskRulePublishRequest;
import com.evops.ticket.mask.dto.MaskRuleVersionVO;
import com.evops.ticket.mask.dto.SensitiveViewLogVO;
import com.evops.ticket.mask.entity.MaskRuleVersion;
import com.evops.ticket.mask.entity.SensitiveViewLog;
import com.evops.ticket.mask.mapper.SensitiveViewLogMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 脱敏管理端：规则版本发布/查询、明文查看留痕审计查询。
 * 仅 ADMIN 角色可调用（鉴权在控制器层）。
 */
@Service
public class MaskAdminService {

    private final MaskRuleService ruleService;
    private final SensitiveViewLogMapper viewLogMapper;
    private final ObjectMapper objectMapper;

    public MaskAdminService(MaskRuleService ruleService, SensitiveViewLogMapper viewLogMapper,
                            ObjectMapper objectMapper) {
        this.ruleService = ruleService;
        this.viewLogMapper = viewLogMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public MaskRuleVersionVO publish(MaskRulePublishRequest request) {
        Map<String, MaskType> rules = new LinkedHashMap<>();
        for (FieldRuleItem item : request.getFieldRules()) {
            MaskType type;
            try {
                type = MaskType.valueOf(item.getMaskType().trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "未知打码类型：" + item.getMaskType() + "（支持 NAME/CONTACT/GENERIC）");
            }
            rules.put(item.getField().trim(), type);
        }
        List<String> roles = request.getWhitelistRoles() == null ? new ArrayList<>()
                : request.getWhitelistRoles();
        MaskRuleVersion row = ruleService.publish(rules, roles, request.getRemark());
        return toVO(row);
    }

    public List<MaskRuleVersionVO> listVersions() {
        return ruleService.listVersions().stream().map(this::toVO).collect(Collectors.toList());
    }

    public MaskRuleVersionVO currentVersion() {
        MaskRuleVersion active = ruleService.getActiveEntity();
        if (active == null) {
            throw new IllegalArgumentException("当前无生效脱敏规则版本");
        }
        return toVO(active);
    }

    /** 明文查看留痕明细分页：按人/角色/业务数据/出口通道/时间区间审计。 */
    public PageVO<SensitiveViewLogVO> pageViewLogs(long page, long size, String username,
                                                    String roleCode, String bizType, String bizId,
                                                    String channel, LocalDateTime from, LocalDateTime to) {
        LambdaQueryWrapper<SensitiveViewLog> wrapper = new LambdaQueryWrapper<>();
        if (username != null && !username.trim().isEmpty()) {
            wrapper.eq(SensitiveViewLog::getUsername, username.trim());
        }
        if (roleCode != null && !roleCode.trim().isEmpty()) {
            wrapper.eq(SensitiveViewLog::getRoleCode, roleCode.trim());
        }
        if (bizType != null && !bizType.trim().isEmpty()) {
            wrapper.eq(SensitiveViewLog::getBizType, bizType.trim());
        }
        if (bizId != null && !bizId.trim().isEmpty()) {
            wrapper.eq(SensitiveViewLog::getBizId, bizId.trim());
        }
        if (channel != null && !channel.trim().isEmpty()) {
            wrapper.eq(SensitiveViewLog::getChannel, channel.trim());
        }
        if (from != null) {
            wrapper.ge(SensitiveViewLog::getViewTime, from);
        }
        if (to != null) {
            wrapper.le(SensitiveViewLog::getViewTime, to);
        }
        wrapper.orderByDesc(SensitiveViewLog::getId);
        Page<SensitiveViewLog> result = viewLogMapper.selectPage(new Page<>(page, size), wrapper);
        List<SensitiveViewLogVO> list = result.getRecords().stream()
                .map(this::toLogVO).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    // ==================== 组装 ====================

    private MaskRuleVersionVO toVO(MaskRuleVersion row) {
        MaskRuleVersionVO vo = new MaskRuleVersionVO();
        BeanUtils.copyProperties(row, vo);
        vo.setFieldRulesRaw(row.getFieldRules());
        List<FieldRuleItem> items = new ArrayList<>();
        try {
            Map<String, String> raw = objectMapper.readValue(
                    row.getFieldRules(), new TypeReference<Map<String, String>>() {
                    });
            raw.forEach((f, t) -> {
                FieldRuleItem item = new FieldRuleItem();
                item.setField(f);
                item.setMaskType(t);
                items.add(item);
            });
        } catch (Exception ignored) {
            // 规则 JSON 异常不阻断版本列表展示（raw 仍返回）
        }
        vo.setFieldRules(items);
        vo.setWhitelistRoles(row.getWhitelistRoles() == null || row.getWhitelistRoles().isEmpty()
                ? new ArrayList<>()
                : Arrays.stream(row.getWhitelistRoles().split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
        return vo;
    }

    private SensitiveViewLogVO toLogVO(SensitiveViewLog row) {
        SensitiveViewLogVO vo = new SensitiveViewLogVO();
        BeanUtils.copyProperties(row, vo);
        return vo;
    }
}
