package com.evops.ticket.sensitive.service;

import com.evops.common.web.RequestContext;
import com.evops.ticket.sensitive.SensitiveHit;
import com.evops.ticket.sensitive.entity.SensitiveHitLog;
import com.evops.ticket.sensitive.mapper.SensitiveHitLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 敏感词命中留痕写入器。
 *
 * <p>独立事务（REQUIRES_NEW）：高危拦截时外层检票事务会回滚，留痕必须在自己的事务里
 * 先落地——否则「谁在什么单据上输入了被拦截的高危内容」将无据可查。
 * 写库失败只打 ERROR，绝不向上抛影响主流程（审计故障不能挡住检票）。
 */
@Component
public class SensitiveHitLogRecorder {

    private static final Logger log = LoggerFactory.getLogger(SensitiveHitLogRecorder.class);

    private final SensitiveHitLogMapper hitLogMapper;

    public SensitiveHitLogRecorder(SensitiveHitLogMapper hitLogMapper) {
        this.hitLogMapper = hitLogMapper;
    }

    /**
     * 逐词写一行命中留痕。
     *
     * @param maskedText 打码后的文本；高危拦截（BLOCKED）时为 null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String scene, String bizRef, String fieldName,
                       String originalText, String maskedText, String action,
                       List<SensitiveHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String operator = RequestContext.operator();
        String requestId = RequestContext.requestId();
        String path = RequestContext.get() == null ? null : RequestContext.get().getPath();
        for (SensitiveHit hit : hits) {
            try {
                SensitiveHitLog row = new SensitiveHitLog();
                row.setScene(scene);
                row.setBizRef(bizRef);
                row.setFieldName(fieldName);
                row.setWordId(hit.getWordId());
                row.setWord(hit.getWord());
                row.setLevel(hit.getLevel().name());
                row.setMatchedText(truncate(hit.getMatchedText(), 255));
                row.setStartCp(hit.getStartCp());
                row.setEndCp(hit.getEndCp());
                row.setOriginalText(originalText);
                row.setMaskedText(maskedText);
                row.setAction(action);
                row.setOperator(operator);
                row.setRequestPath(path);
                row.setRequestId(requestId);
                row.setHitTime(now);
                hitLogMapper.insert(row);
            } catch (Exception e) {
                // 单行失败不影响其余命中落痕
                log.error("敏感词命中留痕写库失败 scene={} word={} span=[{},{})",
                        scene, hit.getWord(), hit.getStartCp(), hit.getEndCp(), e);
            }
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
