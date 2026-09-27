package com.evops.ticket.sensitive.service;

import com.evops.ticket.agent.service.AgentInboundGuard;
import com.evops.ticket.sensitive.SensitiveBlockException;
import com.evops.ticket.sensitive.SensitiveHit;
import com.evops.ticket.sensitive.SensitiveLevel;
import com.evops.ticket.sensitive.TextNormalizer;
import com.evops.ticket.sensitive.config.SensitiveProperties;
import com.evops.ticket.sensitive.entity.SensitiveHitLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 敏感词扫描统一闸门：检票流程所有自由文本入口经此过一遍。
 *
 * <p>处置口径：
 * <ul>
 *   <li>无命中：原文原样返回；</li>
 *   <li>含高危（HIGH）：逐词留痕（BLOCKED）后抛 {@link SensitiveBlockException}，
 *       外层检票事务回滚，本次操作不落库；留痕走独立事务，回滚也保得住；</li>
 *   <li>仅低危（LOW）：命中片段逐码点打码后返回，业务继续，逐词留痕（MASKED）。</li>
 * </ul>
 *
 * <p>回退预案：{@code evops.sensitive.enabled=false} 完全切回旧逻辑（不扫描）；
 * 扫描链路自身任何异常按 {@code fail-open} 处理——默认记 ERROR 后原文放行，
 * 现场检票绝不因扫描故障停摆；平台入站报文默认豁免（非人工输入）。
 */
@Service
public class SensitiveScanService {

    private static final Logger log = LoggerFactory.getLogger(SensitiveScanService.class);

    private final SensitiveProperties properties;
    private final SensitiveWordHolder wordHolder;
    private final SensitiveHitLogRecorder hitLogRecorder;

    public SensitiveScanService(SensitiveProperties properties,
                                SensitiveWordHolder wordHolder,
                                SensitiveHitLogRecorder hitLogRecorder) {
        this.properties = properties;
        this.wordHolder = wordHolder;
        this.hitLogRecorder = hitLogRecorder;
    }

    /**
     * 扫描单个文本字段。
     *
     * @param scene     场景（{@link com.evops.ticket.sensitive.SensitiveScene}）
     * @param bizRef    业务定位（票号/预约号），可为 null
     * @param fieldName 被扫字段名（reason/cancelReason）
     * @param text      被扫原文，null/空串原样返回
     * @return 处置结果（放行文本：无命中为原文，仅低危为打码文本）
     * @throws SensitiveBlockException 命中高危时拦截
     */
    public ScanResult scan(String scene, String bizRef, String fieldName, String text) {
        if (text == null || text.isEmpty() || !properties.isEnabled() || exemptInbound()) {
            return ScanResult.pass(text);
        }

        List<SensitiveHit> hits;
        try {
            hits = wordHolder.current().scan(text);
        } catch (Exception e) {
            // 预案：扫描引擎故障立即切回旧逻辑（fail-open），不阻断检票
            log.error("敏感词扫描异常，本次按原文放行 scene={} bizRef={} field={}",
                    scene, bizRef, fieldName, e);
            if (properties.isFailOpen()) {
                return ScanResult.pass(text);
            }
            throw new SensitiveBlockException(scene, "敏感词扫描暂不可用，请稍后重试或联系管理员切回旧逻辑");
        }

        if (hits.isEmpty()) {
            return ScanResult.pass(text);
        }

        boolean blocked = false;
        for (SensitiveHit hit : hits) {
            if (hit.getLevel() == SensitiveLevel.HIGH) {
                blocked = true;
                break;
            }
        }

        if (blocked) {
            // 留痕先于异常：独立事务提交，外层检票回滚不影响留痕
            safeRecord(scene, bizRef, fieldName, text, null, SensitiveHitLog.ACTION_BLOCKED, hits);
            throw new SensitiveBlockException(scene,
                    "检票备注含禁止内容（命中 " + highHitWords(hits) + "），已拦截，请修改后重试");
        }

        String masked = applyMask(text, hits);
        safeRecord(scene, bizRef, fieldName, text, masked, SensitiveHitLog.ACTION_MASKED, hits);
        return ScanResult.masked(masked, hits);
    }

    /** 仅扫描不处置：管理端「试扫」/排查用，返回全部命中（不打码、不留痕、不抛异常）。 */
    public List<SensitiveHit> preview(String text) {
        if (text == null || text.isEmpty() || !properties.isEnabled()) {
            return java.util.Collections.emptyList();
        }
        return wordHolder.current().scan(text);
    }

    /** 平台入站报文默认豁免（非现场人工输入，误拦会导致平台同步失败）。 */
    private boolean exemptInbound() {
        return AgentInboundGuard.isInbound() && !properties.isScanAgentInbound();
    }

    /** 从后往前替换，前面的命中位置不受影响。 */
    private String applyMask(String text, List<SensitiveHit> hits) {
        String mask = effectiveMaskChar();
        String result = text;
        for (int i = hits.size() - 1; i >= 0; i--) {
            SensitiveHit hit = hits.get(i);
            result = TextNormalizer.replaceSpanByCodePoints(result, hit.getStartCp(), hit.getEndCp(), mask);
        }
        return result;
    }

    private String effectiveMaskChar() {
        String mask = properties.getMaskChar();
        if (mask == null || mask.isEmpty()) {
            return "*";
        }
        // 只取第一个码点作为掩码，防止配置成多字符串把位置撑乱
        return mask.codePointCount(0, mask.length()) >= 1
                ? new String(Character.toChars(mask.codePointAt(0)))
                : "*";
    }

    private String highHitWords(List<SensitiveHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (SensitiveHit hit : hits) {
            if (hit.getLevel() == SensitiveLevel.HIGH) {
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(hit.getWord());
            }
        }
        return sb.toString();
    }

    /** 留痕故障不阻断业务（含高危场景：拦截决定已下，仅审计行缺失需告警补录）。 */
    private void safeRecord(String scene, String bizRef, String fieldName,
                            String original, String masked, String action, List<SensitiveHit> hits) {
        try {
            hitLogRecorder.record(scene, bizRef, fieldName, original, masked, action, hits);
        } catch (Exception e) {
            log.error("敏感词命中留痕失败，{} 决定仍生效 scene={} bizRef={} action={}",
                    SensitiveHitLog.ACTION_BLOCKED.equals(action) ? "拦截" : "打码放行",
                    scene, bizRef, action, e);
        }
    }

    /** 扫描处置结果。 */
    public static final class ScanResult {
        private final String text;
        private final boolean masked;
        private final List<SensitiveHit> hits;

        private ScanResult(String text, boolean masked, List<SensitiveHit> hits) {
            this.text = text;
            this.masked = masked;
            this.hits = hits;
        }

        static ScanResult pass(String text) {
            return new ScanResult(text, false, java.util.Collections.emptyList());
        }

        static ScanResult masked(String text, List<SensitiveHit> hits) {
            return new ScanResult(text, true, hits);
        }

        /** 放行文本：无命中为原文，仅低危为打码文本。 */
        public String getText() {
            return text;
        }

        public boolean isMasked() {
            return masked;
        }

        public List<SensitiveHit> getHits() {
            return hits;
        }
    }
}
