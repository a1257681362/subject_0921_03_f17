package com.evops.ticket.sensitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词 DFA（Trie 确定有限自动机）。
 *
 * <p>不可变快照：由当前生效词库一次性构建，词库热更时整体替换实例（引用切换，无锁读）。
 * 匹配规则：
 * <ul>
 *   <li>在<b>归一骨架</b>上逐位驱动 Trie，全角半角/大小写/插缝干扰在归一阶段已抹平；</li>
 *   <li>贪婪最长匹配：经过终结节点后继续尝试（词库同时有「发票」「代开发票」时命中更长者）；</li>
 *   <li>命中后跳过已匹配区间继续扫描，命中区间互不重叠（打码不重叠、留痕不重复）；</li>
 *   <li>位置经骨架 origin 映射回原文码点区间，区间含被跳过的干扰符（如「黄 牛」整体打码）。</li>
 * </ul>
 */
public final class SensitiveDfa {

    private final Node root = new Node();

    private SensitiveDfa() {
    }

    /** 词库条目：id 为库内主键，word 为词库原文（构建时再归一），level 为分级。 */
    public static final class WordEntry {
        private final long id;
        private final String word;
        private final SensitiveLevel level;

        public WordEntry(long id, String word, SensitiveLevel level) {
            this.id = id;
            this.word = word;
            this.level = level;
        }
    }

    public static SensitiveDfa build(List<WordEntry> entries) {
        SensitiveDfa dfa = new SensitiveDfa();
        for (WordEntry entry : entries) {
            if (entry.word == null) {
                continue;
            }
            TextNormalizer.Skeleton skeleton = TextNormalizer.skeleton(entry.word);
            if (skeleton.length() == 0) {
                continue;
            }
            Node node = dfa.root;
            for (int cp : skeleton.chars()) {
                node = node.children.computeIfAbsent(cp, k -> new Node());
            }
            if (node.wordId != null) {
                // 同一归一形态重复入库：高危优先（宁可拦，不可放）
                if (entry.level == SensitiveLevel.HIGH || node.level != SensitiveLevel.HIGH) {
                    node.wordId = entry.id;
                    node.word = TextNormalizer.normalizeWord(entry.word);
                    node.level = entry.level;
                }
            } else {
                node.wordId = entry.id;
                node.word = TextNormalizer.normalizeWord(entry.word);
                node.level = entry.level;
            }
        }
        return dfa;
    }

    /** 空 DFA：词库未就绪/开关关闭时使用，任何文本都无命中。 */
    public static SensitiveDfa empty() {
        return new SensitiveDfa();
    }

    /**
     * 扫描原文，返回按出现顺序、互不重叠的命中列表（贪婪最长匹配）。
     */
    public List<SensitiveHit> scan(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        TextNormalizer.Skeleton skeleton = TextNormalizer.skeleton(raw);
        int n = skeleton.length();
        if (n == 0) {
            return Collections.emptyList();
        }
        int[] chars = skeleton.chars();
        int[] origin = skeleton.origin();

        List<SensitiveHit> hits = new ArrayList<>();
        int i = 0;
        while (i < n) {
            Node node = root;
            int matchedEnd = -1;
            Node matchedNode = null;
            int j = i;
            while (j < n) {
                Node child = node.children.get(chars[j]);
                if (child == null) {
                    break;
                }
                node = child;
                j++;
                if (node.wordId != null) {
                    matchedEnd = j;
                    matchedNode = node;
                }
            }
            if (matchedNode != null) {
                int startCp = origin[i];
                int endCp = origin[matchedEnd - 1] + 1;
                String matchedText = TextNormalizer.substringByCodePoints(raw, startCp, endCp);
                hits.add(new SensitiveHit(matchedNode.wordId, matchedNode.word, matchedNode.level,
                        matchedText, startCp, endCp));
                i = matchedEnd;
            } else {
                i++;
            }
        }
        return hits;
    }

    /** 是否存在任意高危命中。 */
    public boolean hasBlockHit(List<SensitiveHit> hits) {
        for (SensitiveHit hit : hits) {
            if (hit.isBlock()) {
                return true;
            }
        }
        return false;
    }

    private static final class Node {
        private final Map<Integer, Node> children = new HashMap<>();
        private Long wordId;
        private String word;
        private SensitiveLevel level;
    }
}
