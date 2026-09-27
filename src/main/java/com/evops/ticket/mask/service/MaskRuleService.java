package com.evops.ticket.mask.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.web.RequestContext;
import com.evops.ticket.mask.EffectiveRule;
import com.evops.ticket.mask.MaskType;
import com.evops.ticket.mask.entity.MaskRuleVersion;
import com.evops.ticket.mask.mapper.MaskRuleVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 脱敏规则版本服务。
 *
 * <p>规则变更只追加新版本：发布时把当前 ACTIVE 置 SUPERSEDED（留档不改写），
 * 新版本号 = 现有最大版本 + 1，置 ACTIVE。生效规则在内存缓存一份不可变快照，
 * 发布后立即失效、下次读取按新版本重建（无需重启）。
 *
 * <p>取不到生效规则（表空/读库异常）返回 {@link EffectiveRule#FAIL_CLOSED}：
 * 白名单为空、全员打码——安全失败关闭，绝不因规则缺失放行明文。
 */
@Service
public class MaskRuleService {

    private static final Logger log = LoggerFactory.getLogger(MaskRuleService.class);

    private final MaskRuleVersionMapper ruleMapper;
    private final ObjectMapper objectMapper;
    private volatile EffectiveRule cache;

    public MaskRuleService(MaskRuleVersionMapper ruleMapper, ObjectMapper objectMapper) {
        this.ruleMapper = ruleMapper;
        this.objectMapper = objectMapper;
    }

    /** 当前生效规则（读缓存；缓存失效时从库重建）。 */
    public EffectiveRule current() {
        EffectiveRule snapshot = cache;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (cache == null) {
                cache = loadActive();
            }
            return cache;
        }
    }

    public void invalidateCache() {
        synchronized (this) {
            cache = null;
        }
    }

    private EffectiveRule loadActive() {
        try {
            MaskRuleVersion row = ruleMapper.selectOne(new LambdaQueryWrapper<MaskRuleVersion>()
                    .eq(MaskRuleVersion::getStatus, MaskRuleVersion.STATUS_ACTIVE)
                    .last("LIMIT 1"));
            if (row == null) {
                log.error("脱敏规则无 ACTIVE 版本，按失败关闭处理：全员打码直至规则就绪");
                return EffectiveRule.FAIL_CLOSED;
            }
            Map<String, MaskType> fieldRules = parseFieldRules(row.getFieldRules());
            Set<String> roles = parseRoles(row.getWhitelistRoles());
            return new EffectiveRule(row.getRuleVersion(), fieldRules, roles);
        } catch (Exception e) {
            // 读库/解析异常不得放行明文
            log.error("加载脱敏生效规则失败，按失败关闭处理", e);
            return EffectiveRule.FAIL_CLOSED;
        }
    }

    private Map<String, MaskType> parseFieldRules(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, String> raw = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
            Map<String, MaskType> parsed = new LinkedHashMap<>();
            raw.forEach((field, type) -> {
                if (field != null && !field.trim().isEmpty() && type != null) {
                    parsed.put(field.trim(), MaskType.valueOf(type.trim()));
                }
            });
            return parsed;
        } catch (Exception e) {
            log.error("脱敏规则 field_rules 解析失败，字段映射回退注解默认：{}", json, e);
            return new LinkedHashMap<>();
        }
    }

    private Set<String> parseRoles(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return new LinkedHashSet<>();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ==================== 版本管理 ====================

    /** 全部版本（新版本在前）。 */
    public List<MaskRuleVersion> listVersions() {
        return ruleMapper.selectList(new LambdaQueryWrapper<MaskRuleVersion>()
                .orderByDesc(MaskRuleVersion::getRuleVersion));
    }

    public MaskRuleVersion getActiveEntity() {
        return ruleMapper.selectOne(new LambdaQueryWrapper<MaskRuleVersion>()
                .eq(MaskRuleVersion::getStatus, MaskRuleVersion.STATUS_ACTIVE)
                .last("LIMIT 1"));
    }

    /**
     * 发布新版本（同事务）：旧 ACTIVE 置 SUPERSEDED 留档，新版本 +1 置 ACTIVE。
     *
     * @param fieldRules     字段 → 打码类型（不可为空，键值经校验）
     * @param whitelistRoles 白名单角色（可为空表示全员打码）
     */
    @Transactional(rollbackFor = Exception.class)
    public MaskRuleVersion publish(Map<String, MaskType> fieldRules, List<String> whitelistRoles,
                                   String remark) {
        if (fieldRules == null || fieldRules.isEmpty()) {
            throw new IllegalArgumentException("规则字段映射不能为空");
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, MaskType> e : fieldRules.entrySet()) {
            if (e.getKey() == null || e.getKey().trim().isEmpty() || e.getValue() == null) {
                throw new IllegalArgumentException("字段名与打码类型均不能为空");
            }
            normalized.put(e.getKey().trim(), e.getValue().name());
        }
        String rolesCsv = whitelistRoles == null ? "" : whitelistRoles.stream()
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
        String fieldRulesJson;
        try {
            fieldRulesJson = objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalStateException("规则序列化失败", e);
        }

        MaskRuleVersion current = getActiveEntity();
        if (current != null) {
            current.setStatus(MaskRuleVersion.STATUS_SUPERSEDED);
            current.setUpdateTime(LocalDateTime.now());
            ruleMapper.updateById(current);
        }
        int nextVersion = (current == null ? 0 : current.getRuleVersion()) + 1;
        if (current == null) {
            Integer max = ruleMapper.selectObjs(new LambdaQueryWrapper<MaskRuleVersion>()
                    .select(MaskRuleVersion::getRuleVersion)
                    .orderByDesc(MaskRuleVersion::getRuleVersion)
                    .last("LIMIT 1"))
                    .stream().findFirst().map(o -> Integer.valueOf(String.valueOf(o))).orElse(0);
            nextVersion = max + 1;
        }

        MaskRuleVersion row = new MaskRuleVersion();
        row.setRuleVersion(nextVersion);
        row.setRuleCode(MaskRuleVersion.DEFAULT_RULE_CODE);
        row.setFieldRules(fieldRulesJson);
        row.setWhitelistRoles(rolesCsv);
        row.setStatus(MaskRuleVersion.STATUS_ACTIVE);
        row.setRemark(remark);
        row.setOperator(RequestContext.operator());
        row.setRequestId(RequestContext.requestId());
        row.setBizTime(LocalDateTime.now());
        ruleMapper.insert(row);

        invalidateCache();
        log.warn("脱敏规则发布新版本 v{}：字段{}，白名单[{}]，操作人={}",
                nextVersion, normalized.keySet(), rolesCsv, row.getOperator());
        return row;
    }
}
