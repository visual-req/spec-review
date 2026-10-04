package com.py_spec_qc.core.security;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 送审前对需求文本做敏感信息预检与脱敏，降低文档中疑似密钥/凭证外发到模型的风险。
 * 仅替换「疑似凭证的值」，保留键名与上下文，尽量不影响质量扫描语义。
 */
public final class SecretRedactor {
    public static final String MASK = "***REDACTED***";

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)((?:pass(?:word|wd)?|secret|api[_-]?key|access[_-]?key|secret[_-]?key|client[_-]?secret|token|private[_-]?key|authorization|auth)\\s*[:=]\\s*[\"']?)([^\\s\"',;}]{6,})"),
            Pattern.compile("(?i)(\\b(?:bearer|basic)\\s+)([A-Za-z0-9._\\-+/=]{12,})"),
            Pattern.compile("()((?:AKIA|ASIA)[0-9A-Z]{16})"),
            Pattern.compile("()(\\bsk-[A-Za-z0-9]{16,}\\b)"),
            Pattern.compile("()(-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----)")
    );

    private SecretRedactor() {
    }

    public static Result redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Result(text == null ? "" : text, 0);
        }
        String out = text;
        int count = 0;
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(out);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String secret = m.group(2);
                if (secret == null || secret.isBlank()) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                    continue;
                }
                count++;
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + MASK));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return new Result(out, count);
    }

    public record Result(String text, int count) {
    }
}
