package com.py_spec_qc.core.rules;

import com.py_spec_qc.core.word.DocxTextExtractor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RulesLoader {
    private static final Pattern RULE_HEADER = Pattern.compile("^####\\s+", Pattern.MULTILINE);
    private static final Pattern RULE_BLOCK = Pattern.compile("^####\\s+.*?(?=^####\\s+|\\z)", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern APPLICABLE_INDUSTRY_LINE = Pattern.compile("(?m)^\\s*[-*]?\\s*适用行业\\s*[:：]\\s*(.+?)\\s*$");

    public RulesData load(Path rulesDir, String lang) {
        String l = normalizeLang(lang);
        String requestedIndustry = normalizeIndustry(System.getenv("SPEC_QC_INDUSTRY"));
        if (rulesDir == null) {
            String text = loadBuiltinQualityStandard();
            List<String> rules = splitMarkdownToRules(text);
            int cnt = rules.size();
            return new RulesData(rules, cnt > 0 ? cnt : 1, l, "builtin");
        }

        Path dir = resolveLangRulesDir(rulesDir, l);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException(msgRulesDirMustExist(l) + dir);
        }

        List<Path> ruleFiles;
        try {
            List<Path> all = Files.list(dir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String suf = suffixLower(p);
                        return suf.equals(".md") || suf.equals(".docx") || suf.equals(".doc");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
            ruleFiles = filterFilesByLang(all, l);
        } catch (IOException e) {
            throw new IllegalArgumentException(msgReadRulesDirFailed(l) + dir, e);
        }

        if (ruleFiles.isEmpty()) {
            throw new IllegalArgumentException(msgNoRuleFiles(l) + dir);
        }

        int ruleCount = 0;
        List<String> rules = new ArrayList<>();
        DocxTextExtractor extractor = new DocxTextExtractor();

        boolean hasGenericMd = false;
        boolean hasIndustrySpecificMd = false;
        List<Path> mdFiles = ruleFiles.stream().filter(p -> suffixLower(p).equals(".md")).toList();
        for (Path p : mdFiles) {
            String text;
            try {
                text = Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalArgumentException("读取规则文件失败: " + p, e);
            }
            List<String> inds = parseApplicableIndustries(text);
            if (isGenericIndustry(inds)) {
                hasGenericMd = true;
            } else {
                hasIndustrySpecificMd = true;
            }
        }

        if (requestedIndustry.isEmpty() && hasIndustrySpecificMd && !hasGenericMd) {
            throw new IllegalArgumentException(
                    "检测到行业专属规则文件，但未声明目标行业，已拒绝加载以避免跨行业误扫。"
                    + "请设置环境变量 SPEC_QC_INDUSTRY（例如 SPEC_QC_INDUSTRY=\"银行\"），"
                    + "或在规则文件的“适用行业”中标注“通用”。dir=" + dir);
        }

        for (Path p : ruleFiles) {
            String suf = suffixLower(p);
            if (suf.equals(".doc")) {
                throw new IllegalArgumentException(msgDocNotSupported(l) + p);
            }
            String text;
            if (suf.equals(".md")) {
                try {
                    text = Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalArgumentException(msgReadRuleFileFailed(l) + p, e);
                }

                if (!shouldIncludeByIndustry(text, requestedIndustry, hasGenericMd, hasIndustrySpecificMd, ruleFiles.size())) {
                    continue;
                }

                List<String> mdRules = splitMarkdownToRules(text);
                if (mdRules.isEmpty()) {
                    mdRules = List.of(text == null ? "" : text);
                }
                for (String r : mdRules) {
                    rules.add(ruleFileLabel(l) + p.getFileName() + "\n" + (r == null ? "" : r));
                }
                ruleCount += mdRules.size();
            } else {
                text = extractor.extractDocxText(p);
                rules.add(ruleFileLabel(l) + p.getFileName() + "\n" + (text == null ? "" : text));
                ruleCount += 1;
            }
        }
        return new RulesData(rules, ruleCount, l, dir.toString());
    }

    private static int countRulesInMarkdown(String md) {
        if (md == null || md.isBlank()) {
            return 0;
        }
        return (int) RULE_HEADER.matcher(md).results().count();
    }

    private static List<String> splitMarkdownToRules(String md) {
        if (md == null || md.isBlank()) {
            return List.of();
        }
        Matcher m = RULE_BLOCK.matcher(md);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            String block = m.group(0);
            if (block != null && !block.trim().isEmpty()) {
                out.add(block.trim());
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        int cnt = countRulesInMarkdown(md);
        if (cnt > 0) {
            return List.of(md.trim());
        }
        return List.of(md.trim());
    }

    private static String loadBuiltinQualityStandard() {
        try (InputStream in = RulesLoader.class.getResourceAsStream("/res/quality_standard.md")) {
            if (in == null) {
                throw new IllegalStateException("未找到内置规则文件: /res/quality_standard.md");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取内置规则文件失败", e);
        }
    }

    private static String suffixLower(Path p) {
        String n = p.getFileName().toString();
        int idx = n.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return n.substring(idx).toLowerCase();
    }

    private static String normalizeLang(String lang) {
        if (lang == null) {
            return "zh";
        }
        String t = lang.trim().toLowerCase();
        if (t.isEmpty()) {
            return "zh";
        }
        if (t.startsWith("en")) {
            return "en";
        }
        if (t.startsWith("ja") || t.startsWith("jp")) {
            return "ja";
        }
        if (t.startsWith("zh")) {
            return "zh";
        }
        return "zh";
    }

    private static Path resolveLangRulesDir(Path rulesDir, String lang) {
        Path dir = rulesDir.toAbsolutePath().normalize();
        String l = normalizeLang(lang);
        Path candidate = dir.resolve(l);
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        return dir;
    }

    private static List<Path> filterFilesByLang(List<Path> all, String lang) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        boolean anyTagged = false;
        for (Path p : all) {
            if (hasAnyLangSuffix(p)) {
                anyTagged = true;
                break;
            }
        }
        if (!anyTagged) {
            return all;
        }
        String l = normalizeLang(lang);
        List<Path> out = new ArrayList<>();
        for (Path p : all) {
            if (!hasAnyLangSuffix(p) || hasLangSuffix(p, l)) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean hasAnyLangSuffix(Path p) {
        return hasLangSuffix(p, "zh") || hasLangSuffix(p, "en") || hasLangSuffix(p, "ja");
    }

    private static boolean hasLangSuffix(Path p, String lang) {
        if (p == null) {
            return false;
        }
        String l = normalizeLang(lang);
        String name = p.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        return base.endsWith("_" + l) || base.endsWith("-" + l) || base.endsWith("." + l);
    }

    private static String normalizeIndustry(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.isEmpty() ? "" : t;
    }

    private static List<String> parseApplicableIndustries(String md) {
        if (md == null || md.isBlank()) {
            return List.of();
        }
        Matcher m = APPLICABLE_INDUSTRY_LINE.matcher(md);
        if (!m.find()) {
            return List.of();
        }
        String raw = m.group(1);
        if (raw == null) {
            return List.of();
        }
        String[] parts = raw.split("[\\s,，;；、/|]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p == null) {
                continue;
            }
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    private static boolean shouldIncludeByIndustry(
            String md,
            String requestedIndustry,
            boolean hasGenericMd,
            boolean hasIndustrySpecificMd,
            int totalFileCount
    ) {
        List<String> inds = parseApplicableIndustries(md);
        boolean isGeneric = isGenericIndustry(inds);
        if (!requestedIndustry.isEmpty()) {
            if (isGeneric) {
                return true;
            }
            for (String it : inds) {
                if (it.equalsIgnoreCase(requestedIndustry) || it.contains(requestedIndustry) || requestedIndustry.contains(it)) {
                    return true;
                }
            }
            return false;
        }

        if (hasIndustrySpecificMd && hasGenericMd) {
            return isGeneric;
        }

        if (totalFileCount == 1) {
            return true;
        }

        return true;
    }

    private static boolean isGenericIndustry(List<String> inds) {
        if (inds == null || inds.isEmpty()) {
            return true;
        }
        for (String it : inds) {
            if (it == null) {
                continue;
            }
            String t = it.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.equalsIgnoreCase("通用") || t.startsWith("通用")) {
                return true;
            }
        }
        return false;
    }

    private static String ruleFileLabel(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "[Rule File] ";
        if ("ja".equals(l)) return "【ルールファイル】";
        return "【规则文件】";
    }

    private static String msgRulesDirMustExist(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Rules directory must be an existing directory: ";
        if ("ja".equals(l)) return "ルールディレクトリは既存のディレクトリである必要があります: ";
        return "规则目录必须是已存在的目录: ";
    }

    private static String msgReadRulesDirFailed(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Failed to read rules directory: ";
        if ("ja".equals(l)) return "ルールディレクトリの読み取りに失敗しました: ";
        return "读取规则目录失败: ";
    }

    private static String msgNoRuleFiles(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "No rule files found under rules directory: ";
        if ("ja".equals(l)) return "ルールディレクトリ配下にルールファイルが見つかりません: ";
        return "规则目录下未找到规则文件: ";
    }

    private static String msgReadRuleFileFailed(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Failed to read rule file: ";
        if ("ja".equals(l)) return "ルールファイルの読み取りに失敗しました: ";
        return "读取规则文件失败: ";
    }

    private static String msgDocNotSupported(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Rule file .doc is not supported, please convert to .docx: ";
        if ("ja".equals(l)) return "ルールファイル .doc は未対応です。.docx に変換してください: ";
        return "规则文件暂不支持 .doc，请转换为 .docx: ";
    }
}
