package com.evops.ticket.sensitive;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词 DFA 内核单测：全角半角/大小写/插缝干扰归一、命中位置准确回映原文、
 * 贪婪最长匹配、命中区间不重叠、分级与多场景变体绕过。
 */
class SensitiveDfaTest {

    private SensitiveDfa dfa(String... highWords) {
        return buildWith(Arrays.asList(highWords), Collections.emptyList());
    }

    private SensitiveDfa buildWith(List<String> high, List<String> low) {
        java.util.List<SensitiveDfa.WordEntry> entries = new java.util.ArrayList<>();
        long id = 1;
        for (String w : high) {
            entries.add(new SensitiveDfa.WordEntry(id++, w, SensitiveLevel.HIGH));
        }
        for (String w : low) {
            entries.add(new SensitiveDfa.WordEntry(id++, w, SensitiveLevel.LOW));
        }
        return SensitiveDfa.build(entries);
    }

    @Test
    void plainText_matchesWordWithExactPositions() {
        SensitiveDfa dfa = dfa("黄牛");
        List<SensitiveHit> hits = dfa.scan("发现黄牛在场外");
        assertEquals(1, hits.size());
        SensitiveHit hit = hits.get(0);
        assertEquals("黄牛", hit.getWord());
        assertEquals(2, hit.getStartCp());
        assertEquals(4, hit.getEndCp());
        assertEquals("黄牛", hit.getMatchedText());
    }

    @Test
    void fullWidthLatinAndDigits_areNormalizedToHalfWidth() {
        // 全角字母 Ｈ 经 NFKC->H 再小写 h；词库用半角录入
        SensitiveDfa dfa = dfa("ab12");
        assertTrue(dfa.scan("密码ＸＹ９９错").isEmpty(), "不含 ab12 的文本应无命中");
        List<SensitiveHit> hits = dfa.scan("这里ａｂ１２有问题");
        assertEquals(1, hits.size());
        // 位置覆盖原文中的 4 个全角字符
        assertEquals(2, hits.get(0).getStartCp());
        assertEquals(6, hits.get(0).getEndCp());
        assertEquals("ａｂ１２", hits.get(0).getMatchedText());
    }

    @Test
    void caseInsensitive_asciiWord() {
        SensitiveDfa dfa = dfa("加v");
        List<SensitiveHit> upper = dfa.scan("请加Ｖ联系"); // 全角大写 V
        assertEquals(1, upper.size());
        assertEquals("加v", upper.get(0).getWord());
        assertEquals("加Ｖ", upper.get(0).getMatchedText());

        List<SensitiveHit> mixed = dfa.scan("加V低价");
        assertEquals(1, mixed.size());
        assertEquals(0, mixed.get(0).getStartCp());
        assertEquals(2, mixed.get(0).getEndCp());
    }

    @Test
    void insertedSeparators_areIgnoredButSpanCoversThem() {
        SensitiveDfa dfa = dfa("黄牛");
        // 半角空格插缝
        List<SensitiveHit> spaced = dfa.scan("黄 牛党");
        assertEquals(1, spaced.size());
        assertEquals(0, spaced.get(0).getStartCp());
        assertEquals(3, spaced.get(0).getEndCp(), "区间应覆盖中间的空格");
        assertEquals("黄 牛", spaced.get(0).getMatchedText());

        // 全角空格（U+3000）+ 中点 + 零宽字符
        String zeroWidth = "黄​牛"; // 黄 + U+200B + 牛
        List<SensitiveHit> zw = dfa.scan(zeroWidth);
        assertEquals(1, zw.size());
        assertEquals(3, zw.get(0).getEndCp());

        List<SensitiveHit> dot = dfa.scan("黄·牛");
        assertEquals(1, dot.size());
        assertEquals("黄·牛", dot.get(0).getMatchedText());

        List<SensitiveHit> fullSpace = dfa.scan("黄　牛");
        assertEquals(1, fullSpace.size());
    }

    @Test
    void sentencePunctuationBetweenChars_doesNotMatch() {
        // 逗号/句号不是插缝符：「黄，牛」不应误判为「黄牛」
        SensitiveDfa dfa = dfa("黄牛");
        assertTrue(dfa.scan("黄，牛").isEmpty());
        assertTrue(dfa.scan("黄。牛").isEmpty());
    }

    @Test
    void greedyLongestMatch_whenNestedWords() {
        // 词库同时含短词与长词：命中更长的
        SensitiveDfa dfa = buildWith(Arrays.asList("发票", "代开发票"), Collections.emptyList());
        List<SensitiveHit> hits = dfa.scan("可代开发票");
        assertEquals(1, hits.size());
        assertEquals("代开发票", hits.get(0).getWord());
        assertEquals(1, hits.get(0).getStartCp());
        assertEquals(5, hits.get(0).getEndCp());
    }

    @Test
    void multipleHits_areNonOverlappingAndOrdered() {
        SensitiveDfa dfa = buildWith(Arrays.asList("黄牛", "诈骗"), Arrays.asList("微信"));
        List<SensitiveHit> hits = dfa.scan("黄牛加微信诈骗");
        assertEquals(3, hits.size());
        assertEquals("黄牛", hits.get(0).getWord());
        assertEquals(SensitiveLevel.HIGH, hits.get(0).getLevel());
        assertEquals("微信", hits.get(1).getWord());
        assertEquals(SensitiveLevel.LOW, hits.get(1).getLevel());
        assertEquals("诈骗", hits.get(2).getWord());
        assertEquals(SensitiveLevel.HIGH, hits.get(2).getLevel());
        // 区间互不重叠、首尾相接
        assertEquals(0, hits.get(0).getStartCp());
        assertEquals(2, hits.get(0).getEndCp());
        assertEquals(3, hits.get(1).getStartCp());
        assertEquals(5, hits.get(1).getEndCp());
        assertEquals(5, hits.get(2).getStartCp());
        assertEquals(7, hits.get(2).getEndCp());
    }

    @Test
    void duplicateNormalizedWord_highWins() {
        // 同一归一形态分别按 LOW/HIGH 入库两次：构建后按高危判定
        java.util.List<SensitiveDfa.WordEntry> entries = Arrays.asList(
                new SensitiveDfa.WordEntry(1, "黄牛", SensitiveLevel.LOW),
                new SensitiveDfa.WordEntry(2, "黄 牛", SensitiveLevel.HIGH));
        SensitiveDfa dfa = SensitiveDfa.build(entries);
        List<SensitiveHit> hits = dfa.scan("黄牛");
        assertEquals(1, hits.size());
        assertEquals(SensitiveLevel.HIGH, hits.get(0).getLevel());
    }

    @Test
    void emptyAndNullInputs_returnNoHits() {
        SensitiveDfa dfa = dfa("黄牛");
        assertTrue(dfa.scan(null).isEmpty());
        assertTrue(dfa.scan("").isEmpty());
        assertTrue(dfa.scan("  　· ").isEmpty(), "纯干扰符无骨架，应无命中");
    }

    @Test
    void emptyDfa_neverMatches() {
        assertFalse(SensitiveDfa.empty().scan("黄牛诈骗").iterator().hasNext());
    }

    @Test
    void positionsRemainCorrectAfterMultibyteAndEmojiPrefix() {
        // 码点口径：emoji（增补平面，2 个 char）只占 1 个码点
        SensitiveDfa dfa = dfa("黄牛");
        List<SensitiveHit> hits = dfa.scan("😀黄 牛");
        assertEquals(1, hits.size());
        assertEquals(1, hits.get(0).getStartCp());
        assertEquals(4, hits.get(0).getEndCp());
        // 按码点截取回原文，正确覆盖 emoji 之后的命中片段
        assertEquals("黄 牛", TextNormalizer.substringByCodePoints(
                "😀黄 牛", hits.get(0).getStartCp(), hits.get(0).getEndCp()));
    }
}
