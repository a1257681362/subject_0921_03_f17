package com.evops.ticket.sensitive;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 敏感词扫描前的文本归一化：拆掉观众端常见的全半角/大小写/插入干扰。
 *
 * <p>归一策略（词库词与被扫文本走同一条管线，保证两边对得齐）：
 * <ol>
 *   <li>NFKC 兼容性分解：全角 Ａ→A、１→1、全角标点 → 半角、全角字母大写 Ｈ→H 等；</li>
 *   <li>大小写折叠：A→a（Locale 无关的码点级转换）；</li>
 *   <li>跳过干扰符：空白（含全角空格 U+3000）、零宽字符（U+200B~200F/2060/FEFF）、
 *       常见插缝符号（· . * ~ - _ 及其全角形态），使「黄 牛」「黄·牛」「黄​牛」都等于「黄牛」。</li>
 * </ol>
 *
 * <p>位置准确性：归一后的骨架字符与原码点下标并行保存（{@link Skeleton#origin}），
 * 命中位置一律映射回<b>原文</b>码点区间返回；NFKC 单码点展开（如 ﬁ→fi）时多个骨架字符
 * 共享同一个原码点下标。位置口径为 Unicode 码点偏移（半开区间），中文 BMP 文本与 char 偏移一致。
 */
public final class TextNormalizer {

    /** 插入干扰符号（NFKC 之后的半角形态；全角形态由 NFKC 先行折叠）。 */
    private static final Set<Integer> SEPARATORS = new HashSet<>(Arrays.asList(
            (int) '·', (int) '・', (int) '.', (int) '*', (int) '~',
            (int) '-', (int) '_', (int) '^'));

    private TextNormalizer() {
    }

    /** 构建归一骨架：chars 为折叠后的码点序列，origin[i] 为该码点来自原文的码点下标。 */
    public static Skeleton skeleton(String raw) {
        IntList chars = new IntList();
        IntList origin = new IntList();
        if (raw == null || raw.isEmpty()) {
            return new Skeleton(chars.toArray(), origin.toArray());
        }
        int cpCount = raw.codePointCount(0, raw.length());
        for (int idx = 0; idx < cpCount; idx++) {
            int cp = raw.codePointAt(raw.offsetByCodePoints(0, idx));
            if (isNoise(cp)) {
                continue;
            }
            // 单码点 NFKC：多数为 1→1；展开（ﬁ→fi、①→1）时多个结果共享同一原下标
            String folded = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFKC);
            int n = folded.codePointCount(0, folded.length());
            for (int j = 0; j < n; j++) {
                int mapped = folded.codePointAt(folded.offsetByCodePoints(0, j));
                if (isNoise(mapped)) {
                    continue;
                }
                chars.add(Character.toLowerCase(mapped));
                origin.add(idx);
            }
        }
        return new Skeleton(chars.toArray(), origin.toArray());
    }

    /** 词库词归一：返回折叠后的骨架字符串，用于入库排重与排查。 */
    public static String normalizeWord(String word) {
        Skeleton skeleton = skeleton(word);
        StringBuilder sb = new StringBuilder();
        for (int cp : skeleton.chars) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /**
     * 干扰符判定：空白/零宽字符直接跳过；插缝符号只收窄到一组无实义分隔符，
     * 不跳过句子标点（，。！）以免「黄，牛」这类本不相干的句子被误判。
     */
    public static boolean isNoise(int cp) {
        if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
            return true;
        }
        if ((cp >= 0x200B && cp <= 0x200F) || cp == 0x2060 || cp == 0xFEFF) {
            return true;
        }
        return SEPARATORS.contains(cp);
    }

    /** 按码点下标截取原文片段。 */
    public static String substringByCodePoints(String raw, int startCp, int endCp) {
        int start = raw.offsetByCodePoints(0, startCp);
        int end = raw.offsetByCodePoints(0, endCp);
        return raw.substring(start, end);
    }

    /** 在原文上按码点半开区间 [startCp,endCp) 替换为等长掩码（按码点计）。 */
    public static String replaceSpanByCodePoints(String raw, int startCp, int endCp, String mask) {
        int start = raw.offsetByCodePoints(0, startCp);
        int end = raw.offsetByCodePoints(0, endCp);
        StringBuilder sb = new StringBuilder(raw.length() + (end - start) * 4);
        sb.append(raw, 0, start);
        for (int i = startCp; i < endCp; i++) {
            sb.append(mask);
        }
        sb.append(raw, end, raw.length());
        return sb.toString();
    }

    /** 归一骨架：折叠码点 + 与之一一对应的原文码点下标。 */
    public static final class Skeleton {
        private final int[] chars;
        private final int[] origin;

        Skeleton(int[] chars, int[] origin) {
            this.chars = chars;
            this.origin = origin;
        }

        public int[] chars() {
            return chars;
        }

        public int[] origin() {
            return origin;
        }

        public int length() {
            return chars.length;
        }
    }

    /** 省装箱的 int 动态数组。 */
    private static final class IntList {
        private int[] data = new int[16];
        private int size;

        void add(int v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }

        int[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }
}
