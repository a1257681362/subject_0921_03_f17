package com.evops.ticket.sensitive;

/**
 * 一次敏感词命中（位置口径：被扫<b>原文</b>的 Unicode 码点半开区间
 * {@code [startCp, endCp)}，非归一骨架下标、非 char 下标）。
 *
 * <p>{@code matchedText} 为命中位置上的原文片段（全角/插缝干扰的原样形态），
 * {@code word} 为词库归一形态，二者对照可审计「观众实际输入了什么、命中了哪条词」。
 */
public final class SensitiveHit {

    private final long wordId;
    private final String word;
    private final SensitiveLevel level;
    private final String matchedText;
    private final int startCp;
    private final int endCp;

    public SensitiveHit(long wordId, String word, SensitiveLevel level,
                        String matchedText, int startCp, int endCp) {
        this.wordId = wordId;
        this.word = word;
        this.level = level;
        this.matchedText = matchedText;
        this.startCp = startCp;
        this.endCp = endCp;
    }

    public long getWordId() {
        return wordId;
    }

    public String getWord() {
        return word;
    }

    public SensitiveLevel getLevel() {
        return level;
    }

    public String getMatchedText() {
        return matchedText;
    }

    public int getStartCp() {
        return startCp;
    }

    public int getEndCp() {
        return endCp;
    }

    public boolean isBlock() {
        return level.isBlock();
    }

    @Override
    public String toString() {
        return "SensitiveHit{word='" + word + "', level=" + level
                + ", span=[" + startCp + "," + endCp + "), text='" + matchedText + "'}";
    }
}
