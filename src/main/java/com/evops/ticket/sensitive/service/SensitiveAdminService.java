package com.evops.ticket.sensitive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.evops.common.web.PageVO;
import com.evops.common.web.RequestContext;
import com.evops.ticket.sensitive.SensitiveHit;
import com.evops.ticket.sensitive.SensitiveLevel;
import com.evops.ticket.sensitive.TextNormalizer;
import com.evops.ticket.sensitive.dto.SensitiveHitLogVO;
import com.evops.ticket.sensitive.dto.SensitiveWordUpsertRequest;
import com.evops.ticket.sensitive.dto.SensitiveWordVO;
import com.evops.ticket.sensitive.entity.SensitiveHitLog;
import com.evops.ticket.sensitive.entity.SensitiveWord;
import com.evops.ticket.sensitive.mapper.SensitiveHitLogMapper;
import com.evops.ticket.sensitive.mapper.SensitiveWordMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 敏感词管理端：词库增删/停用（改完即热刷新 DFA）、命中留痕分页审计、上线前试扫。
 */
@Service
public class SensitiveAdminService {

    private final SensitiveWordMapper wordMapper;
    private final SensitiveHitLogMapper hitLogMapper;
    private final SensitiveWordHolder wordHolder;
    private final SensitiveScanService scanService;

    public SensitiveAdminService(SensitiveWordMapper wordMapper,
                                 SensitiveHitLogMapper hitLogMapper,
                                 SensitiveWordHolder wordHolder,
                                 SensitiveScanService scanService) {
        this.wordMapper = wordMapper;
        this.hitLogMapper = hitLogMapper;
        this.wordHolder = wordHolder;
        this.scanService = scanService;
    }

    // ==================== 词库 ====================

    @Transactional(readOnly = true)
    public List<SensitiveWordVO> listWords(String level, String status, String keyword) {
        LambdaQueryWrapper<SensitiveWord> wrapper = new LambdaQueryWrapper<>();
        if (level != null && !level.trim().isEmpty()) {
            wrapper.eq(SensitiveWord::getLevel, level.trim().toUpperCase());
        }
        if (status != null && !status.trim().isEmpty()) {
            wrapper.eq(SensitiveWord::getStatus, status.trim().toUpperCase());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.like(SensitiveWord::getWord, keyword.trim());
        }
        wrapper.orderByAsc(SensitiveWord::getLevel).orderByDesc(SensitiveWord::getId);
        return wordMapper.selectList(wrapper).stream().map(this::toWordVO).collect(Collectors.toList());
    }

    /** 新增词条：归一形态同形唯一（全角/插缝变体不允许重复入库）；提交后热刷新 DFA。 */
    @Transactional(rollbackFor = Exception.class)
    public SensitiveWordVO addWord(SensitiveWordUpsertRequest req) {
        String normalized = TextNormalizer.normalizeWord(req.getWord().trim());
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("敏感词归一后为空（请输入有效词）");
        }
        SensitiveWord exists = wordMapper.selectOne(new LambdaQueryWrapper<SensitiveWord>()
                .eq(SensitiveWord::getNormalizedWord, normalized).last("LIMIT 1"));
        if (exists != null) {
            throw new IllegalArgumentException("词库已存在同形词：" + exists.getWord()
                    + "（级别 " + exists.getLevel() + "，状态 " + exists.getStatus() + "），请直接调整该条");
        }
        LocalDateTime now = LocalDateTime.now();
        SensitiveWord word = new SensitiveWord();
        word.setWord(req.getWord().trim());
        word.setNormalizedWord(normalized);
        word.setLevel(SensitiveLevel.safeValueOf(req.getLevel()).name());
        word.setCategory(trimToNull(req.getCategory()));
        word.setStatus(SensitiveWord.STATUS_ENABLED);
        word.setRemark(trimToNull(req.getRemark()));
        word.setOperator(RequestContext.operator());
        word.setRequestId(RequestContext.requestId());
        word.setBizTime(now);
        wordMapper.insert(word);

        wordHolder.reload();
        return toWordVO(wordMapper.selectById(word.getId()));
    }

    /** 停用（留档可查，不再参与扫描）后热刷新。 */
    @Transactional(rollbackFor = Exception.class)
    public void disableWord(Long id) {
        changeStatus(id, SensitiveWord.STATUS_DISABLED);
    }

    /** 重新启用停用词条后热刷新。 */
    @Transactional(rollbackFor = Exception.class)
    public void enableWord(Long id) {
        changeStatus(id, SensitiveWord.STATUS_ENABLED);
    }

    private void changeStatus(Long id, String status) {
        SensitiveWord word = wordMapper.selectById(id);
        if (word == null) {
            throw new IllegalArgumentException("敏感词不存在：id=" + id);
        }
        if (status.equals(word.getStatus())) {
            return;
        }
        word.setStatus(status);
        word.setOperator(RequestContext.operator());
        word.setRequestId(RequestContext.requestId());
        word.setBizTime(LocalDateTime.now());
        int rows = wordMapper.updateById(word);
        if (rows == 0) {
            throw new IllegalArgumentException("敏感词已被他人变更，请刷新后重试");
        }
        wordHolder.reload();
    }

    /** 立即从库内重建 DFA（运维手动重刷入口）。 */
    public int reload() {
        return wordHolder.reload();
    }

    public int currentWordCount() {
        return wordHolder.getWordCount();
    }

    // ==================== 命中留痕 ====================

    @Transactional(readOnly = true)
    public PageVO<SensitiveHitLogVO> pageHits(long page, long size, String scene,
                                              String level, String action, String keyword) {
        LambdaQueryWrapper<SensitiveHitLog> wrapper = new LambdaQueryWrapper<>();
        if (scene != null && !scene.trim().isEmpty()) {
            wrapper.eq(SensitiveHitLog::getScene, scene.trim().toUpperCase());
        }
        if (level != null && !level.trim().isEmpty()) {
            wrapper.eq(SensitiveHitLog::getLevel, level.trim().toUpperCase());
        }
        if (action != null && !action.trim().isEmpty()) {
            wrapper.eq(SensitiveHitLog::getAction, action.trim().toUpperCase());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w.like(SensitiveHitLog::getWord, keyword.trim())
                    .or().like(SensitiveHitLog::getBizRef, keyword.trim())
                    .or().like(SensitiveHitLog::getOriginalText, keyword.trim()));
        }
        wrapper.orderByDesc(SensitiveHitLog::getId);
        Page<SensitiveHitLog> result = hitLogMapper.selectPage(new Page<>(page, size), wrapper);
        List<SensitiveHitLogVO> list = result.getRecords().stream()
                .map(this::toHitVO).collect(Collectors.toList());
        return new PageVO<>(result.getCurrent(), result.getSize(), result.getTotal(), list);
    }

    /** 试扫：返回当前词库对文本的全部命中（含位置），不落业务不留痕。 */
    public List<SensitiveHit> preview(String text) {
        return scanService.preview(text);
    }

    // ==================== 组装 ====================

    private SensitiveWordVO toWordVO(SensitiveWord word) {
        SensitiveWordVO vo = new SensitiveWordVO();
        BeanUtils.copyProperties(word, vo);
        return vo;
    }

    private SensitiveHitLogVO toHitVO(SensitiveHitLog row) {
        SensitiveHitLogVO vo = new SensitiveHitLogVO();
        BeanUtils.copyProperties(row, vo);
        return vo;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
