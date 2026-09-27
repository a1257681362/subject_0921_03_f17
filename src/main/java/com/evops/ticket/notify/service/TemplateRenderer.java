package com.evops.ticket.notify.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板变量渲染：把 ${varName} 占位替换为变量值。
 * 未提供的变量替换为空串（避免把 ${xxx} 原样发给场务造成困惑）。
 * 渲染在发送当次完成并随发送记录快照，模板之后改动与已发内容无关。
 */
public final class TemplateRenderer {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\$\\{\\s*([A-Za-z0-9_]+)\\s*}");

    private TemplateRenderer() {
    }

    public static String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String value = vars.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
