package com.evops.ticket.sensitive.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.ticket.sensitive.SensitiveDfa;
import com.evops.ticket.sensitive.SensitiveLevel;
import com.evops.ticket.sensitive.entity.SensitiveWord;
import com.evops.ticket.sensitive.mapper.SensitiveWordMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生效敏感词 DFA 的内存持有者。
 *
 * <p>启动后从 t_sensitive_word 装载全部 ENABLED 词构建不可变 {@link SensitiveDfa}；
 * 管理端增删/停用词后调用 {@link #reload()} 原子替换（引用切换，读路径无锁）。
 * 装载失败不影响应用启动，保留空 DFA（任何文本无命中），等下一次 reload 恢复——
 * 与「扫描链路故障不挡检票」的预案一致。
 */
@Component
public class SensitiveWordHolder {

    private static final Logger log = LoggerFactory.getLogger(SensitiveWordHolder.class);

    private final SensitiveWordMapper wordMapper;
    private final AtomicReference<SensitiveDfa> dfa = new AtomicReference<>(SensitiveDfa.empty());
    private volatile int wordCount;
    private volatile boolean loadAttempted;

    public SensitiveWordHolder(SensitiveWordMapper wordMapper) {
        this.wordMapper = wordMapper;
    }

    /** 应用就绪后装载词库；装载失败只告警，不阻断启动。 */
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        try {
            reload();
        } catch (Exception e) {
            log.error("敏感词库启动装载失败，本次运行暂按空词库放行（不阻断检票）", e);
        }
    }

    /**
     * 从库内重建 DFA 并原子切换。
     *
     * @return 本次装载的生效词数
     */
    public synchronized int reload() {
        List<SensitiveWord> rows = wordMapper.selectList(new LambdaQueryWrapper<SensitiveWord>()
                .eq(SensitiveWord::getStatus, SensitiveWord.STATUS_ENABLED));
        List<SensitiveDfa.WordEntry> entries = new ArrayList<>(rows.size());
        int skipped = 0;
        for (SensitiveWord row : rows) {
            if (row.getWord() == null || row.getWord().trim().isEmpty()) {
                skipped++;
                continue;
            }
            entries.add(new SensitiveDfa.WordEntry(
                    row.getId() == null ? 0L : row.getId(),
                    row.getWord(),
                    SensitiveLevel.safeValueOf(row.getLevel())));
        }
        SensitiveDfa rebuilt = SensitiveDfa.build(entries);
        dfa.set(rebuilt);
        wordCount = entries.size();
        loadAttempted = true;
        log.info("敏感词 DFA 已（重新）装载：生效词 {} 个，跳过空词 {} 个", entries.size(), skipped);
        return entries.size();
    }

    public SensitiveDfa current() {
        return dfa.get();
    }

    public int getWordCount() {
        return wordCount;
    }

    /** 是否已尝试过装载（测试/管理端可据此提示「词库未就绪」）。 */
    public boolean isLoadAttempted() {
        return loadAttempted;
    }
}
