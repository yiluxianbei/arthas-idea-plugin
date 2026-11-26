package com.github.idea.arthas.plugin.utils;

import java.util.Collection;
import java.util.StringJoiner;

/**
 * trace -E 模式转义工具。
 *
 * <p>Arthas CLI 会先做一层转义解析，所以命令文本里的正则特殊字符需要两个反斜杠，
 * 最终交给 -E 的才是单反斜杠正则。</p>
 *
 * <p>匿名类方法名中的 {@code *} 是通配符，必须转成正则 {@code .*}；
 * 不能转成字面量 {@code \*}，否则匹配不到实际的 {@code Outer$1}。</p>
 *
 * <p>普通类名的 {@code .} 不做转义：正则中的 {@code .} 仍能匹配字面点，
 * 这样可以保持生成的命令与旧版一致，避免出现大量不必要的反斜杠。</p>
 *
 * @author arthas idea plugin
 */
public final class TracePatternUtils {

    private TracePatternUtils() {
    }

    public static String joinEscaped(Collection<String> values) {
        StringJoiner joiner = new StringJoiner("|");
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                joiner.add(escapeRegexPattern(value));
            }
        }
        return joiner.toString();
    }

    /**
     * 将类名/方法名转换为 trace -E 命令文本中的正则片段。
     */
    public static String escapeRegexPattern(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '$':
                    builder.append("\\\\$");
                    break;
                case '*':
                    // 匿名类模式 Outer*$* 中的 * 是通配符，不是字面量。
                    builder.append(".*");
                    break;
                case '\\':
                    builder.append("\\\\\\\\");
                    break;
                default:
                    builder.append(c);
                    break;
            }
        }
        return builder.toString();
    }
}