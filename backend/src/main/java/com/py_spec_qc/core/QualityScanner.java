package com.py_spec_qc.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.py_spec_qc.core.ai.DeepSeekClient;
import com.py_spec_qc.core.ai.ModelOutputParser;
import com.py_spec_qc.core.config.AppConfig;
import com.py_spec_qc.core.config.ConfigLoader;
import com.py_spec_qc.core.model.FileProgress;
import com.py_spec_qc.core.model.Issue;
import com.py_spec_qc.core.rules.RulesData;
import com.py_spec_qc.core.rules.RulesLoader;
import com.py_spec_qc.core.word.DocxTextExtractor;
import com.py_spec_qc.core.xlsx.XlsxIO;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class QualityScanner {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final Pattern SORT_MISSING_HINT = Pattern.compile("遗漏\\s*排序|缺少\\s*排序|排序条件\\s*(遗漏|缺少|未)|没有定义\\s*顺序|顺序\\s*没有定义|未定义\\s*顺序|未说明\\s*排序|未明确\\s*排序");
    private static final Pattern SORT_BY_FIELD_ORDER = Pattern.compile("(默认)?按.{1,30}(升序|降序|倒序|正序|倒排)");
    private static final Pattern SORT_RANGE = Pattern.compile("从(新|旧|高|低|大|小|晚|早).{0,6}到(新|旧|高|低|大|小|晚|早)");
    private static final Pattern SORT_PRIORITY = Pattern.compile("(最新|最晚|最大|最高).{0,6}优先|(最早|最旧|最小|最低).{0,6}优先");
    private static final Pattern TIME_SEMANTIC_WORD = Pattern.compile("本周|上周|下周|本月|上月|下月|今年|去年|最近|近日|近\\s*\\d+\\s*(天|日)|\\d+\\s*(天|日|周|月|年)\\s*(以内|之内)");
    private static final Pattern ETC_LIST_WORD = Pattern.compile("(等等|等\\s|等[，。；,;、\\)）]|等$)");

    private final RulesLoader rulesLoader = new RulesLoader();
    private final ConfigLoader configLoader = new ConfigLoader();
    private final DocxTextExtractor extractor = new DocxTextExtractor();
    private final DeepSeekClient deepSeekClient = new DeepSeekClient();
    private final ModelOutputParser outputParser = new ModelOutputParser();
    private final XlsxIO xlsx = new XlsxIO();

    private static String msgStartFile(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Start";
        if ("ja".equals(l)) return "開始";
        return "开始";
    }

    private static String msgParseDoc(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Parsing document";
        if ("ja".equals(l)) return "ドキュメントを解析中";
        return "解析文档";
    }

    private static String msgFail(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Failed";
        if ("ja".equals(l)) return "失敗";
        return "失败";
    }

    private static String msgScanRule(int from, int to, int total, String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Scanning rules " + from + "-" + to + "/" + total;
        if ("ja".equals(l)) return "ルールをスキャン中 " + from + "-" + to + "/" + total;
        return "扫描规则 " + from + "-" + to + "/" + total;
    }

    private static String msgReqModel(int from, int to, int total, String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Requesting model (" + from + "-" + to + "/" + total + ")";
        if ("ja".equals(l)) return "モデルをリクエスト中 (" + from + "-" + to + "/" + total + ")";
        return "请求模型 (" + from + "-" + to + "/" + total + ")";
    }

    private static String msgFoundIssues(int count, String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Found " + count + " issues";
        if ("ja".equals(l)) return count + " 個の問題を発見しました";
        return "已发现问题 " + count + " 个";
    }

    private static String msgWriteReport(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Writing report";
        if ("ja".equals(l)) return "レポートを作成中";
        return "写入报告";
    }

    private static String msgDone(String lang) {
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Done";
        if ("ja".equals(l)) return "完了";
        return "完成";
    }

    private static String msgFailWithReason(String lang, String reason) {
        String r = shortReason(reason);
        if (r.isBlank()) {
            return msgFail(lang);
        }
        String l = normalizeLang(lang);
        if ("en".equals(l)) return "Failed: " + r;
        if ("ja".equals(l)) return "失敗: " + r;
        return "失败: " + r;
    }

    private static String shortReason(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return "";
        }
        t = t.replace("\r\n", "\n").replace("\r", "\n");
        int nl = t.indexOf('\n');
        if (nl >= 0) {
            t = t.substring(0, nl).trim();
        }
        if (t.length() > 220) {
            t = t.substring(0, 220);
        }
        return t;
    }

    public List<Path> scanReqDirPaths(Path reqDir, Path outDir, Path rulesDir, String lang, ProgressCallback progressCallback) {
        if (reqDir == null) {
            throw new IllegalArgumentException("-req must be an existing directory: null");
        }
        Path req = reqDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(req)) {
            throw new IllegalArgumentException("-req must be an existing directory: " + req);
        }

        RulesData rulesData = rulesLoader.load(rulesDir, lang);
        AppConfig config = configLoader.load();

        List<Path> wordFiles = listWordFiles(req);
        if (wordFiles.isEmpty()) {
            return List.of();
        }

        Path requestedOutDir = outDir == null ? null : outDir.toAbsolutePath().normalize();
        Path defaultWorkDir = pickWritableWorkDir(config, req);
        Path logPath = resolveLogPath(config, defaultWorkDir);
        appendScanLog(
                logPath,
                "scan start req_dir=" + req
                        + " out_dir=" + (requestedOutDir == null ? "" : requestedOutDir)
                        + " rules_dir=" + (rulesDir == null ? "" : rulesDir.toAbsolutePath().normalize())
                        + " rules_lang=" + (rulesData.lang() == null ? "" : rulesData.lang())
                        + " rules_source=" + (rulesData.source() == null ? "" : rulesData.source())
                        + " rule_count=" + rulesData.ruleCount()
        );
        Path resolvedOutDir = requestedOutDir != null
                ? requestedOutDir
                : defaultWorkDir.resolve("output").toAbsolutePath().normalize();

        try {
            Files.createDirectories(resolvedOutDir);
        } catch (IOException e) {
            if (requestedOutDir != null) {
                throw new RuntimeException("Operation not permitted: '" + resolvedOutDir + "'", e);
            }
            Path workDir = pickWritableWorkDir(config, req);
            resolvedOutDir = workDir.resolve("output").toAbsolutePath().normalize();
            try {
                Files.createDirectories(resolvedOutDir);
            } catch (IOException ee) {
                throw new RuntimeException("Operation not permitted: '" + resolvedOutDir + "'", ee);
            }
        }

        if (!dirIsWritable(resolvedOutDir)) {
            Path workDir = pickWritableWorkDir(config, req);
            resolvedOutDir = workDir.resolve("output").toAbsolutePath().normalize();
            try {
                Files.createDirectories(resolvedOutDir);
            } catch (IOException e) {
                throw new RuntimeException("Operation not permitted: '" + resolvedOutDir + "'", e);
            }
        }

        List<Path> outputPaths = new ArrayList<>();
        for (Path wordPath : wordFiles) {
            String startedAt = nowIsoSeconds();
            long startedNs = System.nanoTime();
            appendScanLog(logPath, "file start file=" + wordPath.getFileName().toString() + " started_at=" + startedAt);
            if (progressCallback != null) {
                FileProgress fp = new FileProgress();
                fp.fileName = wordPath.getFileName().toString();
                fp.status = msgStartFile(lang);
                fp.startedAt = startedAt;
                progressCallback.onUpdate(fp);
            }

            Path outPath = resolvedOutDir.resolve(stem(wordPath.getFileName().toString()) + ".xlsx");
            outputPaths.add(outPath);

            String suf = suffixLower(wordPath);
            if (suf.equals(".doc")) {
                String err = normalizeLang(lang).equals("en")
                        ? "'.doc' is not supported yet. Please convert to '.docx'."
                        : (normalizeLang(lang).equals("ja")
                        ? "'.doc' は未対応です。'.docx' に変換してください。"
                        : "暂不支持 .doc，请转换为 .docx");
                xlsx.writeError(outPath, wordPath.getFileName().toString(), err, lang);
                notifyDone(progressCallback, wordPath, startedAt, startedNs, rulesData.ruleCount(), 0, msgFailWithReason(lang, err), outPath, List.of());
                appendScanLog(logPath, "file failed file=" + wordPath.getFileName().toString() + " stage=validate err=unsupported_doc elapsed_ms=" + ((System.nanoTime() - startedNs) / 1_000_000L));
                continue;
            }

            if (progressCallback != null) {
                FileProgress fp = new FileProgress();
                fp.fileName = wordPath.getFileName().toString();
                fp.status = msgParseDoc(lang);
                progressCallback.onUpdate(fp);
            }
            String requirementText = "";
            try {
                requirementText = extractor.extractDocxTextWithLocations(wordPath);
            } catch (Exception e) {
                String err = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                xlsx.writeError(outPath, wordPath.getFileName().toString(), err, lang);
                notifyDone(progressCallback, wordPath, startedAt, startedNs, rulesData.ruleCount(), 0, msgFailWithReason(lang, err), outPath, List.of());
                appendScanLog(logPath, "file failed file=" + wordPath.getFileName().toString() + " stage=parse_docx err=" + err + " elapsed_ms=" + ((System.nanoTime() - startedNs) / 1_000_000L));
                continue;
            }
            if (requirementText == null || requirementText.isBlank()) {
                String err = normalizeLang(lang).equals("en")
                        ? "The Word document is empty or cannot be parsed."
                        : (normalizeLang(lang).equals("ja")
                        ? "Wordドキュメントが空、または解析できません。"
                        : "Word 文档内容为空或无法解析");
                xlsx.writeError(outPath, wordPath.getFileName().toString(), err, lang);
                notifyDone(progressCallback, wordPath, startedAt, startedNs, rulesData.ruleCount(), 0, msgFailWithReason(lang, err), outPath, List.of());
                appendScanLog(logPath, "file failed file=" + wordPath.getFileName().toString() + " stage=parse_docx err=empty_or_unreadable elapsed_ms=" + ((System.nanoTime() - startedNs) / 1_000_000L));
                continue;
            }

            if (config.deepseekApiKey == null || config.deepseekApiKey.isBlank()) {
                String err = normalizeLang(lang).equals("en")
                        ? "LLM api_key is not configured. Set llm.api_key in executable/config.yaml (deepseek.api_key is also supported), or set env LLM_API_KEY / DEEPSEEK_API_KEY."
                        : (normalizeLang(lang).equals("ja")
                        ? "LLM api_key が未設定です。executable/config.yaml の llm.api_key（deepseek.api_key 互換）を設定するか、環境変数 LLM_API_KEY / DEEPSEEK_API_KEY を設定してください。"
                        : "LLM api_key 未配置。请在 executable/config.yaml 设置 llm.api_key（兼容 deepseek.api_key），或设置环境变量 LLM_API_KEY / DEEPSEEK_API_KEY。");
                xlsx.writeError(outPath, wordPath.getFileName().toString(), err, lang);
                notifyDone(progressCallback, wordPath, startedAt, startedNs, rulesData.ruleCount(), 0, msgFailWithReason(lang, err), outPath, List.of());
                appendScanLog(logPath, "file failed file=" + wordPath.getFileName().toString() + " stage=validate err=deepseek_api_key_missing elapsed_ms=" + ((System.nanoTime() - startedNs) / 1_000_000L));
                continue;
            }

            List<Issue> issueItems = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int totalRules = Math.max(1, rulesData.ruleCount());
            List<String> rules = (rulesData.rules() == null || rulesData.rules().isEmpty())
                    ? List.of("")
                    : rulesData.rules();
            totalRules = Math.max(1, rules.size());

            boolean modelFailed = false;
            String modelErr = "";
            String modelStage = "";
            int ruleChunkSize = (config.scanRuleChunkSize == null || config.scanRuleChunkSize <= 0) ? 5 : config.scanRuleChunkSize;
            appendScanLog(logPath, "scan config file=" + wordPath.getFileName().toString() + " rule_chunk_size=" + ruleChunkSize + " llm_timeout_seconds=" + ((config.deepseekTimeoutSeconds == null || config.deepseekTimeoutSeconds <= 0) ? 60 : config.deepseekTimeoutSeconds));
            for (int start = 0; start < rules.size(); start += ruleChunkSize) {
                int end = Math.min(rules.size(), start + ruleChunkSize);
                int from = start + 1;
                int to = end;
                if (progressCallback != null) {
                    FileProgress fp = new FileProgress();
                    fp.fileName = wordPath.getFileName().toString();
                    fp.status = msgScanRule(from, to, totalRules, lang);
                    fp.ruleCount = totalRules;
                    fp.issueCount = issueItems.size();
                    fp.issues = issueItems;
                    progressCallback.onUpdate(fp);
                }
                String chunkText = String.join("\n\n---\n\n", rules.subList(start, end));
                try {
                    long chunkNs = System.nanoTime();
                    appendScanLog(logPath, "model start file=" + wordPath.getFileName().toString() + " rules=" + from + "-" + to + "/" + totalRules + " issues=" + issueItems.size());
                    if (progressCallback != null) {
                        FileProgress fp = new FileProgress();
                        fp.fileName = wordPath.getFileName().toString();
                        fp.status = msgReqModel(from, to, totalRules, lang);
                        fp.ruleCount = totalRules;
                        fp.issueCount = issueItems.size();
                        fp.issues = issueItems;
                        progressCallback.onUpdate(fp);
                    }
                    String content = deepSeekClient.chatCompletions(
                            config.deepseekBaseUrl,
                            config.deepseekApiKey,
                            config.deepseekModel,
                            config.deepseekTimeoutSeconds,
                            buildMessages(chunkText, requirementText, from, to, totalRules, lang)
                    );
                    JsonNode obj = outputParser.parseJsonObject(content);
                    JsonNode issues = obj.get("issues");
                    if (issues == null || !issues.isArray()) {
                        throw new IllegalArgumentException("模型输出不符合要求：issues 必须是数组");
                    }
                    List<Issue> parsed = parseIssues(issues);
                    for (Issue it : parsed) {
                        if (looksLikeNoIssue(it)) {
                            continue;
                        }
                        if (looksLikeSortingFalsePositive(it)) {
                            continue;
                        }
                        if (looksLikeTimeRuleSortingFalsePositive(it)) {
                            continue;
                        }
                        if (looksLikeCancelGoHomeCopyFalsePositive(it)) {
                            continue;
                        }
                        if (looksLikeOrderReverseRuleVersionFalsePositive(it)) {
                            continue;
                        }
                        if (looksLikeTimeRuleDauMauFalsePositive(it)) {
                            continue;
                        }
                        if (looksLikeRoleMissingPermissionPopupFalsePositive(it)) {
                            continue;
                        }
                        if (looksLikeNumericFormatRuleDefaultFalsePositive(it)) {
                            continue;
                        }
                        if (looksLikeTypoRuleAckPacketFalsePositive(it)) {
                            continue;
                        }
                        String key = fingerprint(it);
                        if (seen.add(key)) {
                            it.seq = String.valueOf(issueItems.size() + 1);
                            if (it.reviewStatus == null || it.reviewStatus.isBlank()) {
                                it.reviewStatus = "pending";
                            }
                            if (it.reviewUpdatedAt == null || it.reviewUpdatedAt.isBlank()) {
                                it.reviewUpdatedAt = nowIsoSeconds();
                            }
                            issueItems.add(it);
                        }
                    }
                    writeReviewJson(outPath, wordPath, issueItems);
                    if (progressCallback != null) {
                        FileProgress fp = new FileProgress();
                        fp.fileName = wordPath.getFileName().toString();
                        fp.status = msgFoundIssues(issueItems.size(), lang);
                        fp.ruleCount = totalRules;
                        fp.issueCount = issueItems.size();
                        fp.issues = issueItems;
                        progressCallback.onUpdate(fp);
                    }
                    appendScanLog(logPath, "model ok file=" + wordPath.getFileName().toString() + " rules=" + from + "-" + to + "/" + totalRules + " issues=" + issueItems.size() + " elapsed_ms=" + ((System.nanoTime() - chunkNs) / 1_000_000L));
                } catch (Exception e) {
                    modelFailed = true;
                    modelErr = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
                    modelStage = "rules=" + from + "-" + to + "/" + totalRules;
                    appendScanLog(logPath, "model failed file=" + wordPath.getFileName().toString() + " " + modelStage + " err=" + modelErr);
                    appendScanLog(logPath, stackTraceString(e));
                    break;
                }
            }

            if (modelFailed) {
                xlsx.writeError(outPath, wordPath.getFileName().toString(), modelErr, lang);
                notifyDone(progressCallback, wordPath, startedAt, startedNs, totalRules, 0, msgFailWithReason(lang, modelErr), outPath, List.of());
                appendScanLog(logPath, "file failed file=" + wordPath.getFileName().toString() + " stage=model " + modelStage + " err=" + modelErr + " elapsed_ms=" + ((System.nanoTime() - startedNs) / 1_000_000L));
                continue;
            }

            if (progressCallback != null) {
                FileProgress fp = new FileProgress();
                fp.fileName = wordPath.getFileName().toString();
                fp.status = msgWriteReport(lang);
                progressCallback.onUpdate(fp);
            }
            xlsx.writeIssues(outPath, wordPath.getFileName().toString(), issueItems, lang);
            writeReviewJson(outPath, wordPath, issueItems);
            notifyDone(progressCallback, wordPath, startedAt, startedNs, totalRules, issueItems.size(), msgDone(lang), outPath, issueItems);
            appendScanLog(logPath, "file done file=" + wordPath.getFileName().toString() + " issues=" + issueItems.size() + " out=" + outPath.toAbsolutePath().normalize() + " elapsed_ms=" + ((System.nanoTime() - startedNs) / 1_000_000L));
        }

        return outputPaths;
    }

    private static void writeReviewJson(Path xlsxPath, Path sourceDocPath, List<Issue> issues) {
        Path jsonPath = reviewJsonPath(xlsxPath);
        Map<String, Object> root = new HashMap<>();
        root.put("file_name", sourceDocPath == null ? "" : sourceDocPath.getFileName().toString());
        root.put("source_path", sourceDocPath == null ? "" : sourceDocPath.toAbsolutePath().normalize().toString());
        root.put("revised_path", "");
        root.put("issues", issues == null ? List.of() : issues);
        try {
            Files.createDirectories(jsonPath.getParent());
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Files.writeString(jsonPath, json, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    private static Path reviewJsonPath(Path xlsxPath) {
        Path p = xlsxPath.toAbsolutePath().normalize();
        String name = p.getFileName().toString();
        if (name.toLowerCase().endsWith(".xlsx")) {
            name = name.substring(0, name.length() - 5) + ".review.json";
        } else {
            name = name + ".review.json";
        }
        return p.getParent().resolve(name);
    }

    private static List<Map<String, Object>> buildMessages(String qualityStandardText, String requirementText, int from, int to, int total, String lang) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "你是资深需求质量专家。请严格按要求输出 JSON，不要输出任何额外文字。"
        ));
        String normalizedLang = normalizeLang(lang);
        String outputLanguage = normalizedLang.equals("en") ? "English" : (normalizedLang.equals("ja") ? "Japanese" : "Chinese");
        String severitySpec = normalizedLang.equals("en") ? "High/Medium/Low" : "高/中/低";
        String userContent = ""
                + "请基于下面的质量规范，对需求文档进行质量扫描。\n"
                + "要求：\n"
                + "1) 输出必须是 JSON object，包含字段 issues。\n"
                + "2) issues 是数组，每项包含：severity(" + severitySpec + ")、category、description、evidence_page、evidence_section、evidence_paragraph、evidence_excerpt、suggestion、suggestion_html、related_standard。\n"
                + "3) evidence_paragraph 请填写“对应定位行”的完整段落原文（包含有问题片段前后的上下文，尽量原样复制，不要省略/不要用省略号），不要包含前缀定位标记。\n"
                + "4) evidence_excerpt 请填写段落中“有问题的那一小段原文”，必须是 evidence_paragraph 的子串，便于前端高亮。\n"
                + "5) related_standard 必须填写“命中的检查规则”的编号及标题，不要填写分类词（例如：易读性/一致性）。\n"
                + "   格式要求：从下方【质量规范】里提取对应规则的“编号 + 标题”，但不要包含任何 Markdown 符号（例如 ###/####）。例如：3.2.1 需求必须可验证。\n"
                + "5.1) 语言要求：severity/category/description/suggestion/suggestion_html/related_standard 的文本必须使用 " + outputLanguage + "。evidence_* 字段必须从【需求文档内容】原样拷贝，禁止翻译。\n"
                + "6) 若某条规则已符合规范（无需修改/无问题/符合要求），不要输出为 issue。\n"
                + "6.1) 输出规模限制：issues 数组最多 12 条；每个字段（description/evidence_section/evidence_paragraph/evidence_excerpt/suggestion/related_standard）长度不超过 400 字符，超过则截断；suggestion_html 不超过 2000 字符。\n"
                + "7) 关于排序：只要需求里出现“按<字段>升序/降序/倒序/从新到旧/最新优先”等语义，就视为已定义排序字段与顺序，不要判为“遗漏排序条件/顺序未定义”。例如：默认按政策提交日期降序排序。\n"
                + "8) 关于时间表达：仅当语义涉及明确的时间单位/日期/时点/延迟/定时等，才按时间边界规则检查；仅表示操作先后（如“点击后/提交后/跳转后/完成后”）不视为时间表达。\n"
                + "9) 关于“无”：若某段落内容明确为“无/暂无/不适用/不涉及/N/A”（表示该项不存在或无需提供），不要对该段落提出改进建议，不要输出 issue。\n"
                + "10) 关于时间语义：仅当出现“本周/上周/下周/本月/上月/下月/今年/去年/最近/近日/近x天”等时间语义词，才适用“时间语义不清（本周）”类规则；像“默认按政策提交日期降序排序”“点击后跳转”等不属于该规则。\n"
                + "11) 关于相对时间：仅当出现“x天/周/月/年 + 以内/之内/最近/近x天”等时间窗口语义时，才适用“相对时间表达不清（一个月之内/5天/一年）”类规则；像“按创建日期降序排序/按提交日期升序排序”属于排序规则，不属于相对时间。\n"
                + "12) 关于边缘场景：仅当需求涉及实时刷新、频繁访问、强交互或多端并发等特征时，才按“易遗漏处理类：刷新/断网/多设备并发等边缘场景”检查；低频、纯展示或一次性流程不应强行套用本条。\n"
                + "13) 关于埋点（用户行为统计）：埋点/漏斗/转化/留存等通常仅适用于 ToC 产品的运营增长场景。对于企业内部系统或多数 ToB 系统，不应默认输出“埋点需求遗漏”为问题。\n"
                + "14) 关于关键词匹配：像“等（等等/等）”这类用词缺陷，只能在证据段落/摘录中确实出现该关键词时才输出对应 issue；如果扫描对象句子里没有出现该关键词，不要输出该问题。\n"
                + "15) 关于规则1（使用“等/等等”）：仅当证据段落/摘录中真实出现“等/等等”用于列举时，才允许输出该条 issue。\n"
                + "16) suggestion_html：当建议涉及“校验矩阵/判定矩阵/字段清单/样例表格/术语表（名词解释）/对称流程清单（正向-逆向）/边界与异常清单/用户旅程清单（触达-召回）/角色清单（角色-职责-权限）/逆向流程清单（撤销-回滚-补偿）/对象旅程清单（生命周期矩阵）” 等结构化内容时，请输出一个可直接渲染的 HTML 模板（优先用 table）。若命中“对称业务流程缺失”，建议必须用表格输出（至少包含：正向流程步骤/节点、对应逆向流程、触发条件、前置状态、处置动作、数据影响/回滚、通知/对账影响）。若命中“逆向流程缺失造成开发的过程、测试的过程中需要反复的确认各种细节”，建议必须用表格输出（至少包含：正向节点/动作、可能的逆向场景、触发条件、前置状态、处置动作、数据回滚/补偿、对外通知/对账影响、验收要点）。若命中“需求遗漏：边界/异常遗漏”，建议必须用表格输出（至少包含：触发条件/边界值、期望行为、错误码/提示、补偿/重试/回滚、影响范围、验收用例）。若命中“用户旅程梳理不完整导致隐含功能遗漏”，建议必须用表格输出（至少包含：旅程阶段、用户动作/触发点、系统能力/隐含功能、输入输出、通知/埋点/风控、验收要点）。若命中“关键角色遗漏（专家/顾问/运营/审核等）”，建议必须用表格输出（至少包含：角色、职责/目标、触发参与节点、可执行操作、可见数据范围、审批/审计要求、补齐建议）。若命中“对象旅程梳理不完整导致关键对象处理遗漏（合同/协议等）”，建议必须用表格输出，横轴为生命周期阶段（创建/流转/查询/变更/归档或作废），纵轴为关键对象或子流程，并补齐每格的规则与验收点。若建议补充术语/名词解释，必须用表格输出（至少包含：术语、定义、使用范围/口径、示例、备注）。禁止输出 Markdown。只允许使用这些标签：table/thead/tbody/tr/th/td/p/ul/ol/li/br/strong/b/code/pre。禁止任何属性（包括 style/on*），禁止 script/a/img。\n"
                + "   若命中“需求场景/模块遗漏”，建议必须用表格输出（至少包含：场景/模块、入口/触发、角色、前置条件、主要流程/规则、输出/影响、异常/边界、验收要点）。\n"
                + "   特别地：若建议涉及“权限矩阵”，必须分别输出两张表：RBAC 矩阵（角色 x 功能/操作）和数据权限矩阵（角色 x 数据范围/对象/字段），并提供可填写的表头与占位行。\n"
                + "4) 需求文档内容里每行都带定位标记，例如：[页=3][章节=1.2 用户登录] 某段落内容。\n"
                + "5) evidence_page 填写对应行的页号数字（例如 3）。若需求内容里没有页号信息则留空。\n"
                + "6) evidence_section 填写对应行的章节值（例如 1.2 用户登录），必须包含章节编号与标题。\n"
                + "6) 本次仅扫描第 " + from + "-" + to + " 条规则（共 " + total + " 条），只输出本次规则范围内发现的问题。\n"
                + "质量规范如下：\n"
                + qualityStandardText + "\n\n"
                + "需求文档内容如下：\n"
                + requirementText;
        Map<String, Object> user = new HashMap<>();
        user.put("role", "user");
        user.put("content", userContent);
        messages.add(user);
        return messages;
    }

    private static String normalizeLang(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if (s.isEmpty()) {
            return "zh";
        }
        if (s.startsWith("zh")) {
            return "zh";
        }
        if (s.startsWith("ja") || s.startsWith("jp")) {
            return "ja";
        }
        if (s.startsWith("en")) {
            return "en";
        }
        return "zh";
    }

    private static List<Issue> parseIssues(JsonNode issuesArray) {
        List<Issue> out = new ArrayList<>();
        int seq = 0;
        for (JsonNode it : issuesArray) {
            if (it == null || !it.isObject()) {
                continue;
            }
            seq += 1;
            Issue issue = new Issue();
            issue.seq = String.valueOf(seq);
            issue.severity = textOrEmpty(it.get("severity"));
            issue.category = textOrEmpty(it.get("category"));
            issue.description = textOrEmpty(it.get("description"));
            issue.evidencePage = textOrEmpty(it.get("evidence_page"));
            issue.evidenceSection = textOrEmpty(it.get("evidence_section"));
            issue.evidenceExcerpt = textOrEmpty(it.get("evidence_excerpt"));
            issue.evidenceParagraph = textOrEmpty(it.get("evidence_paragraph"));
            String legacy = textOrEmpty(it.get("evidence"));
            if ((issue.evidenceExcerpt == null || issue.evidenceExcerpt.isBlank()) && legacy != null && !legacy.isBlank()) {
                ParsedEvidence pe = parseLegacyEvidence(legacy);
                if ((issue.evidenceSection == null || issue.evidenceSection.isBlank()) && pe.section != null) {
                    issue.evidenceSection = pe.section;
                }
                issue.evidenceExcerpt = pe.excerpt;
            }
            if (issue.evidenceParagraph == null || issue.evidenceParagraph.isBlank()) {
                issue.evidenceParagraph = issue.evidenceExcerpt;
            }
            issue.suggestion = textOrEmpty(it.get("suggestion"));
            issue.suggestionHtml = textOrEmpty(it.get("suggestion_html"));
            issue.relatedStandard = textOrEmpty(it.get("related_standard"));
            issue.relatedStandard = sanitizeRelatedStandard(issue.relatedStandard);
            if (isNoneParagraph(issue.evidenceParagraph) || isNoneParagraph(issue.evidenceExcerpt)) {
                continue;
            }
            if (looksLikeEtcListRule(issue) && !hasEtcListWord(issue)) {
                continue;
            }
            if ((issue.suggestionHtml == null || issue.suggestionHtml.isBlank()) && looksLikeGlossarySuggestion(issue)) {
                issue.suggestionHtml = defaultGlossaryTableHtml();
            }
            if ((issue.suggestionHtml == null || issue.suggestionHtml.isBlank()) && looksLikeScenarioModuleMissing(issue)) {
                issue.suggestionHtml = defaultScenarioModuleTableHtml();
            }
            out.add(issue);
        }
        return out;
    }

    private record ParsedEvidence(String section, String excerpt) {
    }

    private static ParsedEvidence parseLegacyEvidence(String evidence) {
        String raw = evidence == null ? "" : evidence.trim();
        if (raw.isEmpty()) {
            return new ParsedEvidence("", "");
        }
        String section = "";
        String excerpt = raw;
        int idx = raw.indexOf(':');
        if (idx > 0) {
            String left = raw.substring(0, idx).trim();
            String right = raw.substring(idx + 1).trim();
            if (!right.isEmpty()) {
                excerpt = right;
                section = left.replace("段落", "段落").replace("标题", "标题");
            }
        }
        section = section.replace("段落", "段落").replace("标题", "标题").replace("：", "").trim();
        if (section.startsWith("段落")) {
            section = section;
        }
        if (section.endsWith(":")) {
            section = section.substring(0, section.length() - 1).trim();
        }
        return new ParsedEvidence(section, excerpt);
    }

    private static String fingerprint(Issue it) {
        if (it == null) {
            return "";
        }
        return normalize(it.category) + "|" + normalize(it.description) + "|" + normalize(it.evidenceSection) + "|" + normalize(it.evidenceExcerpt) + "|" + normalize(it.relatedStandard);
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace("\r\n", "\n");
        if (t.length() > 500) {
            return t.substring(0, 500);
        }
        return t;
    }

    private static boolean isNoneParagraph(String s) {
        String t = normalizeNoneToken(s);
        if (t.isEmpty()) {
            return false;
        }
        if (t.equals("无") || t.equals("暂无") || t.equals("不适用") || t.equals("不涉及")) {
            return true;
        }
        return t.equals("n/a") || t.equals("na");
    }

    private static String normalizeNoneToken(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "";
        }
        while (t.endsWith("。") || t.endsWith("，") || t.endsWith("；") || t.endsWith(";") || t.endsWith(".") || t.endsWith(":") || t.endsWith("：")) {
            t = t.substring(0, t.length() - 1).trim();
            if (t.isEmpty()) {
                return "";
            }
        }
        return t.toLowerCase();
    }

    private static boolean looksLikeEtcListRule(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        if (rel.isBlank()) {
            return false;
        }
        return rel.startsWith("1.") || rel.contains("使用“等/等等”") || rel.contains("“等/等等”");
    }

    private static boolean hasEtcListWord(Issue it) {
        if (it == null) {
            return false;
        }
        String ev = normalize(it.evidenceExcerpt);
        String pg = normalize(it.evidenceParagraph);
        String combined = (ev + "\n" + pg).trim();
        if (combined.isEmpty()) {
            return false;
        }
        return ETC_LIST_WORD.matcher(combined).find();
    }

    private static Path resolveLogPath(AppConfig config, Path defaultWorkDir) {
        String envWork = System.getenv("SPEC_QC_WORK_DIR");
        if (envWork != null && !envWork.trim().isEmpty()) {
            return Path.of(envWork.trim()).toAbsolutePath().normalize().resolve("logs").resolve("spec-qc.log");
        }
        if (config != null && config.workDir != null) {
            return config.workDir.toAbsolutePath().normalize().resolve("logs").resolve("spec-qc.log");
        }
        if (defaultWorkDir != null) {
            return defaultWorkDir.toAbsolutePath().normalize().resolve("logs").resolve("spec-qc.log");
        }
        return Path.of("").toAbsolutePath().normalize().resolve("work").resolve("logs").resolve("spec-qc.log");
    }

    private static void appendScanLog(Path logPath, String line) {
        if (logPath == null || line == null) {
            return;
        }
        String t = line.trim();
        if (t.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(logPath.getParent());
            String out = nowIsoSeconds() + " " + t + "\n";
            Files.writeString(logPath, out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private static String stackTraceString(Throwable t) {
        if (t == null) {
            return "";
        }
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            String s = sw.toString();
            if (s.length() > 8000) {
                return s.substring(0, 8000);
            }
            return s;
        } catch (Exception e) {
            return t.toString();
        }
    }

    private static boolean looksLikeGlossarySuggestion(Issue it) {
        if (it == null) {
            return false;
        }
        String a = normalize(it.description);
        String b = normalize(it.suggestion);
        String c = normalize(it.relatedStandard);
        String combined = (a + " " + b + " " + c).toLowerCase();
        return combined.contains("术语")
                || combined.contains("名词解释")
                || combined.contains("术语表")
                || combined.contains("glossary");
    }

    private static String defaultGlossaryTableHtml() {
        return ""
                + "<table>\n"
                + "<thead><tr><th>术语</th><th>定义</th><th>使用范围/口径</th><th>示例</th><th>备注</th></tr></thead>\n"
                + "<tbody>\n"
                + "<tr><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td></tr>\n"
                + "<tr><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td></tr>\n"
                + "</tbody>\n"
                + "</table>";
    }

    private static boolean looksLikeScenarioModuleMissing(Issue it) {
        if (it == null) {
            return false;
        }
        String a = normalize(it.description);
        String b = normalize(it.suggestion);
        String c = normalize(it.relatedStandard);
        String combined = (a + " " + b + " " + c).toLowerCase();
        return combined.contains("需求场景/模块遗漏")
                || combined.contains("场景/模块遗漏")
                || combined.contains("场景遗漏")
                || combined.contains("模块遗漏");
    }

    private static String defaultScenarioModuleTableHtml() {
        return ""
                + "<table>\n"
                + "<thead><tr><th>场景/模块</th><th>入口/触发</th><th>角色</th><th>前置条件</th><th>主要流程/规则</th><th>输出/影响</th><th>异常/边界</th><th>验收要点</th></tr></thead>\n"
                + "<tbody>\n"
                + "<tr><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td></tr>\n"
                + "<tr><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td><td>（待补充）</td></tr>\n"
                + "</tbody>\n"
                + "</table>";
    }

    private static String textOrEmpty(JsonNode n) {
        return n == null ? "" : (n.isNull() ? "" : n.asText(""));
    }

    private static boolean looksLikeNoIssue(Issue it) {
        if (it == null) {
            return true;
        }
        String desc = normalize(it.description);
        String sug = normalize(it.suggestion);
        String cat = normalize(it.category);
        String rel = normalize(it.relatedStandard);
        String combined = (desc + " " + sug + " " + cat + " " + rel).trim();
        if (combined.isEmpty()) {
            return true;
        }
        return combined.contains("已符合规范")
                || combined.contains("已符合要求")
                || combined.contains("符合规范")
                || combined.contains("符合要求")
                || combined.contains("无需修改")
                || combined.contains("不需要修改")
                || combined.contains("不需修改")
                || combined.contains("无需调整")
                || combined.contains("无问题")
                || combined.contains("没有问题")
                || combined.contains("未发现问题");
    }

    private static boolean looksLikeSortingFalsePositive(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        String desc = normalize(it.description);
        String cat = normalize(it.category);
        boolean maybeSortRule = rel.contains("53") || rel.contains("排序") || cat.contains("排序") || desc.contains("排序") || desc.contains("顺序");
        if (!maybeSortRule) {
            return false;
        }
        String combined = (desc + " " + cat + " " + rel).trim();
        if (!SORT_MISSING_HINT.matcher(combined).find()) {
            return false;
        }
        String evidence = normalize(it.evidenceParagraph);
        if (evidence.isBlank()) {
            evidence = normalize(it.evidenceExcerpt);
        }
        return hasExplicitSort(evidence);
    }

    private static boolean looksLikeCancelGoHomeCopyFalsePositive(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        if (rel.isBlank()) {
            return false;
        }
        boolean maybeRule5 = rel.startsWith("5.") || (rel.contains("5.") && rel.contains("文案") && rel.contains("误解"));
        if (!maybeRule5) {
            return false;
        }
        String evidence = (normalize(it.evidenceExcerpt) + "\n" + normalize(it.evidenceParagraph)).trim();
        if (evidence.isBlank()) {
            return false;
        }
        if (!evidence.contains("取消")) {
            return false;
        }
        if (!evidence.contains("返回")) {
            return false;
        }
        if (!(evidence.contains("首页") || evidence.toLowerCase().contains("app首页"))) {
            return false;
        }
        return evidence.contains("按钮");
    }

    private static boolean looksLikeOrderReverseRuleVersionFalsePositive(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        if (rel.isBlank()) {
            return false;
        }
        boolean maybeRule6 = rel.startsWith("6.") || (rel.contains("6.") && rel.contains("顺序") && rel.contains("倒序") && rel.contains("误解"));
        if (!maybeRule6) {
            return false;
        }
        String evidence = (normalize(it.evidenceExcerpt) + "\n" + normalize(it.evidenceParagraph)).trim();
        if (evidence.isBlank()) {
            return false;
        }
        boolean looksLikeVersionRule = evidence.contains("版本") && evidence.contains("版本号") && evidence.contains("+1") && evidence.contains("+0.1");
        if (!looksLikeVersionRule) {
            return false;
        }
        return evidence.contains("修订") || evidence.contains("规范") || evidence.contains("重大调整");
    }

    private static boolean looksLikeTimeRuleDauMauFalsePositive(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        if (rel.isBlank()) {
            return false;
        }
        boolean maybeTimeRule = rel.contains("时间") || rel.contains("本周") || rel.contains("近日") || rel.contains("近") || rel.contains("相对时间");
        if (!maybeTimeRule) {
            return false;
        }
        String evidence = (normalize(it.evidenceExcerpt) + "\n" + normalize(it.evidenceParagraph)).trim();
        if (evidence.isBlank()) {
            return false;
        }
        String e = evidence.toUpperCase();
        boolean hasDauMau = e.contains("DAU") || e.contains("MAU") || evidence.contains("日活") || evidence.contains("月活");
        if (!hasDauMau) {
            return false;
        }
        boolean hasAmbiguousTimeWords = evidence.contains("本周")
                || evidence.contains("上周")
                || evidence.contains("下周")
                || evidence.contains("本月")
                || evidence.contains("上月")
                || evidence.contains("下月")
                || evidence.contains("今年")
                || evidence.contains("去年")
                || evidence.contains("近日")
                || evidence.contains("最近")
                || evidence.contains("近");
        return !hasAmbiguousTimeWords;
    }

    private static boolean looksLikeRoleMissingPermissionPopupFalsePositive(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        if (rel.isBlank()) {
            return false;
        }
        boolean maybeRoleMissingRule = rel.startsWith("16.")
                || rel.startsWith("17.")
                || rel.contains("关键角色遗漏")
                || rel.contains("角色遗漏");
        if (!maybeRoleMissingRule) {
            return false;
        }
        String evidence = (normalize(it.evidenceExcerpt) + "\n" + normalize(it.evidenceParagraph)).trim();
        if (evidence.isBlank()) {
            return false;
        }
        boolean looksLikePermissionPopup = evidence.contains("账户权限不足")
                && (evidence.contains("开通") || evidence.contains("权限"))
                && (evidence.contains("网点") || evidence.contains("视频坐席"));
        return looksLikePermissionPopup;
    }

    private static boolean looksLikeNumericFormatRuleDefaultFalsePositive(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        if (rel.isBlank()) {
            return false;
        }
        boolean maybeRule21 = rel.startsWith("21.") || (rel.contains("21.") && rel.contains("小数") && rel.contains("显示格式"));
        if (!maybeRule21) {
            return false;
        }
        String evidence = (normalize(it.evidenceExcerpt) + "\n" + normalize(it.evidenceParagraph)).trim();
        if (evidence.isBlank()) {
            return false;
        }
        boolean saysDefaultRule = evidence.contains("默认") && (evidence.contains("小数") || evidence.contains("小数位") || evidence.contains("两位小数"));
        boolean saysCalcDefined = evidence.contains("计算") || evidence.contains("规则") || evidence.contains("四舍五入") || evidence.contains("舍入");
        return saysDefaultRule && saysCalcDefined;
    }

    private static boolean looksLikeTypoRuleAckPacketFalsePositive(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        if (rel.isBlank()) {
            return false;
        }
        boolean maybeRule79 = rel.startsWith("79.") || (rel.contains("79.") && rel.contains("错别字"));
        if (!maybeRule79) {
            return false;
        }
        String evidence = (normalize(it.evidenceExcerpt) + "\n" + normalize(it.evidenceParagraph)).trim();
        if (evidence.isBlank()) {
            return false;
        }
        if (!evidence.contains("发送")) {
            return false;
        }
        if (!evidence.contains("应答报文")) {
            return false;
        }
        return evidence.contains("同步") || evidence.contains("（同步）");
    }

    private static boolean looksLikeTimeRuleSortingFalsePositive(Issue it) {
        if (it == null) {
            return false;
        }
        String rel = normalize(it.relatedStandard);
        String desc = normalize(it.description);
        String cat = normalize(it.category);
        boolean maybeTimeRule = rel.startsWith("11.")
                || rel.startsWith("12.")
                || rel.contains("时间语义不清")
                || rel.contains("相对时间表达不清")
                || cat.contains("时间")
                || desc.contains("时间语义不清")
                || desc.contains("相对时间");
        if (!maybeTimeRule) {
            return false;
        }
        String evidence = normalize(it.evidenceParagraph);
        if (evidence.isBlank()) {
            evidence = normalize(it.evidenceExcerpt);
        }
        if (evidence.isBlank()) {
            return false;
        }
        if (!hasExplicitSort(evidence)) {
            return false;
        }
        return !TIME_SEMANTIC_WORD.matcher(evidence).find();
    }

    private static boolean hasExplicitSort(String text) {
        String t = normalize(text);
        if (t.isBlank()) {
            return false;
        }
        return SORT_BY_FIELD_ORDER.matcher(t).find()
                || SORT_RANGE.matcher(t).find()
                || SORT_PRIORITY.matcher(t).find()
                || t.contains("降序")
                || t.contains("倒序")
                || t.contains("正序")
                || t.contains("升序");
    }

    private static String sanitizeRelatedStandard(String s) {
        String t = s == null ? "" : s.trim();
        t = t.replace("\r\n", "\n");
        t = t.replaceFirst("^#+\\s*", "");
        t = t.replaceFirst("^[-*]\\s*", "");
        return t.trim();
    }

    private static void notifyDone(
            ProgressCallback progressCallback,
            Path wordPath,
            String startedAt,
            long startedNs,
            int ruleCount,
            int issueCount,
            String status,
            Path outputPath,
            List<Issue> issues
    ) {
        if (progressCallback == null) {
            return;
        }
        FileProgress fp = new FileProgress();
        fp.fileName = wordPath.getFileName().toString();
        fp.ruleCount = ruleCount;
        fp.issueCount = issueCount;
        fp.status = status;
        fp.outputPath = outputPath.toAbsolutePath().toString();
        fp.startedAt = startedAt;
        fp.endedAt = nowIsoSeconds();
        fp.durationMs = (System.nanoTime() - startedNs) / 1_000_000L;
        fp.issues = issues == null ? List.of() : issues;
        progressCallback.onUpdate(fp);
    }

    private static List<Path> listWordFiles(Path reqDir) {
        try {
            return Files.list(reqDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String suf = suffixLower(p);
                        return suf.equals(".docx") || suf.equals(".doc");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to list directory: " + reqDir, e);
        }
    }

    private static String nowIsoSeconds() {
        return OffsetDateTime.now(ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS).format(ISO_SECONDS);
    }

    private static String suffixLower(Path p) {
        String n = p.getFileName().toString();
        int idx = n.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return n.substring(idx).toLowerCase();
    }

    private static String stem(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx < 0 ? fileName : fileName.substring(0, idx);
    }

    private static boolean dirIsWritable(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            return false;
        }
        try {
            Path tmp = Files.createTempFile(dir, "._spec_qc_", ".tmp");
            Files.writeString(tmp, "1", StandardCharsets.UTF_8);
            Files.deleteIfExists(tmp);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path pickWritableWorkDir(AppConfig config, Path reqDir) {
        List<Path> candidates = new ArrayList<>();
        if (config.workDir != null) {
            candidates.add(config.workDir);
        }
        candidates.add(Path.of(System.getProperty("user.home"), "工作", "work"));
        candidates.add(Path.of("").toAbsolutePath().normalize().resolve("work"));
        candidates.add(Path.of(System.getProperty("java.io.tmpdir"), "spec_qc_work"));
        for (Path c : candidates) {
            Path cc = c.toAbsolutePath().normalize();
            if (dirIsWritable(cc)) {
                return cc;
            }
        }
        return reqDir;
    }

    private static Path fallbackCopyToWorkDir(Path reqDir, Path workDir) {
        Path dstReqDir = workDir.resolve("req_copy").toAbsolutePath().normalize();
        try {
            Files.createDirectories(dstReqDir);
        } catch (IOException e) {
            return reqDir;
        }
        try {
            Files.list(dstReqDir).filter(Files::isRegularFile).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        try {
            Files.list(reqDir).filter(Files::isRegularFile).forEach(src -> {
                String suf = suffixLower(src);
                if (!suf.equals(".docx") && !suf.equals(".doc")) {
                    return;
                }
                try {
                    Files.copy(src, dstReqDir.resolve(src.getFileName().toString()));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        return dstReqDir;
    }
}
