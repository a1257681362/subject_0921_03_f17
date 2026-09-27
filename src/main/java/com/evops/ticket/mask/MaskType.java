package com.evops.ticket.mask;

/**
 * 敏感字段打码类型。每种类型有固定的打码样式，且对「已打码字符串」（含 *）再次
 * 处理保持幂等：防御序列化链路对同一值二次套规则（如快照回放）时越打越花。
 * 真实姓名/联系方式不含 *，以「已含 *」识别已打码值是安全的。
 */
public enum MaskType {

    /** 姓名：保留首位，其余逐位替换为 *（周洋→周*，欧阳修→欧**）。 */
    NAME {
        @Override
        public String mask(String value) {
            if (value == null || value.isEmpty() || alreadyMasked(value)) {
                return value;
            }
            if (value.length() == 1) {
                return "*";
            }
            StringBuilder sb = new StringBuilder(value.length());
            sb.append(value.charAt(0));
            for (int i = 1; i < value.length(); i++) {
                sb.append('*');
            }
            return sb.toString();
        }
    },

    /**
     * 联系方式：11 位手机号保留前 3 后 4（13812348000→138****8000）；
     * 其它长度（座机/短号）保留首尾各 1 位。再次打码结果不变。
     */
    CONTACT {
        @Override
        public String mask(String value) {
            if (value == null || value.isEmpty() || alreadyMasked(value)) {
                return value;
            }
            String digits = value.trim();
            if (digits.length() == 11 && digits.chars().allMatch(Character::isDigit)) {
                return digits.substring(0, 3) + "****" + digits.substring(7);
            }
            return GENERIC.mask(digits);
        }
    },

    /** 通用：长度 ≤2 全 *；否则保留首尾各一位，中间替换为 *。 */
    GENERIC {
        @Override
        public String mask(String value) {
            if (value == null || value.isEmpty() || alreadyMasked(value)) {
                return value;
            }
            if (value.length() <= 2) {
                return value.length() == 1 ? "*" : "**";
            }
            StringBuilder sb = new StringBuilder(value.length());
            sb.append(value.charAt(0));
            for (int i = 1; i < value.length() - 1; i++) {
                sb.append('*');
            }
            sb.append(value.charAt(value.length() - 1));
            return sb.toString();
        }
    };

    private static boolean alreadyMasked(String value) {
        return value.indexOf('*') >= 0;
    }

    public abstract String mask(String value);
}
