package com.py_spec_qc.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.py_spec_qc.core.security.NetworkGuard;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeepSeekClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private static final Object LOG_LOCK = new Object();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    public String chatCompletions(String baseUrl, String apiKey, String model, Integer timeoutSeconds, boolean allowExternal, List<Map<String, Object>> messages) {
        String requestId = UUID.randomUUID().toString();
        long startedNs = System.nanoTime();
        String url = configuredRequestUrl(baseUrl);
        String apiKeyMasked = maskSecret(apiKey);
        int timeoutSec = (timeoutSeconds == null || timeoutSeconds <= 0) ? 60 : timeoutSeconds;
        boolean external = NetworkGuard.isExternal(url);
        if (external && !allowExternal) {
            log(requestId, "blocked_external", Map.of(
                    "url", safeUrl(url),
                    "reason", "llm.allow_external=false",
                    "hint", "switch to an intranet/private model, or set SPEC_QC_ALLOW_EXTERNAL_LLM=1 to accept data egress"
            ));
            throw new IllegalStateException("检测到外部 LLM 地址且 llm.allow_external=false，已阻止发送需求文档以避免数据出域。请改用内网/私有模型，或显式设置 llm.allow_external=true（环境变量 SPEC_QC_ALLOW_EXTERNAL_LLM）。");
        }
        log(requestId, "llm_url", Map.of(
                "LLM_URL", "***LLM URL*** " + safeUrl(url),
                "model", safeStr(model),
                "auth_header", "Authorization",
                "auth_scheme", "Bearer",
                "api_key_masked", apiKeyMasked,
                "timeout_seconds", String.valueOf(timeoutSec),
                "data_external", String.valueOf(external),
                "data_boundary_warning", external ? "需求文档内容将发送到外部模型，存在数据出域风险" : ""
        ));
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("temperature", 0);
        payload.put("response_format", Map.of("type", "json_object"));

        byte[] body;
        try {
            body = MAPPER.writeValueAsBytes(payload);
        } catch (IOException e) {
            Map<String, String> fields = new HashMap<>();
            fields.put("provider", "deepseek");
            fields.put("url", safeUrl(url));
            fields.put("model", safeStr(model));
            fields.put("auth_header", "Authorization");
            fields.put("auth_scheme", "Bearer");
            fields.put("api_key_masked", apiKeyMasked);
            fields.put("message_count", String.valueOf(messages == null ? 0 : messages.size()));
            fields.put("error", safeStr(e.getMessage()));
            fields.put("stack", stackTrace(e));
            log(requestId, "serialize_error", fields);
            throw new IllegalArgumentException("Failed to serialize DeepSeek request", e);
        }

        String bodySha256 = sha256Hex(body);
        Map<String, String> startFields = new HashMap<>();
        startFields.put("provider", "deepseek");
        startFields.put("url", safeUrl(url));
        startFields.put("method", "POST");
        startFields.put("model", safeStr(model));
        startFields.put("auth_header", "Authorization");
        startFields.put("auth_scheme", "Bearer");
        startFields.put("api_key_masked", apiKeyMasked);
        startFields.put("timeout_seconds", String.valueOf(timeoutSec));
        startFields.put("message_count", String.valueOf(messages == null ? 0 : messages.size()));
        startFields.put("request_bytes", String.valueOf(body.length));
        startFields.put("request_sha256", bodySha256);
        startFields.put("sensitive_logging", String.valueOf(sensitiveLoggingEnabled()));
        startFields.put("messages_summary", summarizeMessages(messages));
        log(requestId, "request_start", startFields);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Map<String, String> fields = new HashMap<>();
            fields.put("provider", "deepseek");
            fields.put("url", safeUrl(url));
            fields.put("model", safeStr(model));
            fields.put("auth_header", "Authorization");
            fields.put("auth_scheme", "Bearer");
            fields.put("api_key_masked", apiKeyMasked);
            fields.put("elapsed_ms", String.valueOf(elapsedMs(startedNs)));
            fields.put("error", safeStr(e.getMessage()));
            fields.put("stack", stackTrace(e));
            log(requestId, "request_error", fields);
            throw new RuntimeException("DeepSeek API request failed: " + e.getMessage(), e);
        }

        int status = resp.statusCode();
        byte[] respBody = resp.body() == null ? new byte[0] : resp.body();
        String respText = new String(respBody, StandardCharsets.UTF_8);
        String headerRequestId = firstHeader(resp, "x-request-id");
        if (headerRequestId.isBlank()) {
            headerRequestId = firstHeader(resp, "openai-request-id");
        }
        log(requestId, "response_received", Map.of(
                "provider", "deepseek",
                "url", safeUrl(url),
                "api_key_masked", apiKeyMasked,
                "status", String.valueOf(status),
                "elapsed_ms", String.valueOf(elapsedMs(startedNs)),
                "response_bytes", String.valueOf(respBody.length),
                "resp_x_request_id", headerRequestId,
                "resp_retry_after", firstHeader(resp, "retry-after"),
                "resp_content_type", firstHeader(resp, "content-type"),
                "response_preview", previewResponse(respText)
        ));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            Map<String, String> err = extractErrorFields(respText);
            Map<String, String> fields = new HashMap<>();
            fields.put("provider", "deepseek");
            fields.put("url", safeUrl(url));
            fields.put("model", safeStr(model));
            fields.put("auth_header", "Authorization");
            fields.put("auth_scheme", "Bearer");
            fields.put("api_key_masked", apiKeyMasked);
            fields.put("status", String.valueOf(status));
            fields.put("elapsed_ms", String.valueOf(elapsedMs(startedNs)));
            fields.put("error_message", err.getOrDefault("message", ""));
            fields.put("error_type", err.getOrDefault("type", ""));
            fields.put("error_code", err.getOrDefault("code", ""));
            fields.put("response_preview", previewResponse(respText));
            log(requestId, "http_error", fields);
            throw new RuntimeException("DeepSeek API HTTPError: " + resp.statusCode() + ": " + respText);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(respText);
        } catch (IOException e) {
            log(requestId, "invalid_json", Map.of(
                    "provider", "deepseek",
                    "url", safeUrl(url),
                    "status", String.valueOf(status),
                    "elapsed_ms", String.valueOf(elapsedMs(startedNs)),
                    "response_preview", previewResponse(respText),
                    "error", safeStr(e.getMessage())
            ));
            throw new RuntimeException("DeepSeek API invalid JSON response: " + respText, e);
        }

        Map<String, String> usage = extractUsageFields(root);
        if (!usage.isEmpty()) {
            Map<String, String> fields = new HashMap<>();
            fields.put("provider", "deepseek");
            fields.put("url", safeUrl(url));
            fields.put("status", String.valueOf(status));
            fields.put("elapsed_ms", String.valueOf(elapsedMs(startedNs)));
            fields.putAll(usage);
            log(requestId, "response_usage", fields);
        }

        String out = extractAssistantContent(root);
        if (out.isBlank()) {
            log(requestId, "missing_content", Map.of(
                    "provider", "deepseek",
                    "url", safeUrl(url),
                    "status", String.valueOf(status),
                    "elapsed_ms", String.valueOf(elapsedMs(startedNs)),
                    "root_preview", previewResponse(respText)
            ));
            throw new RuntimeException("DeepSeek API response missing choices[0].message.content");
        }
        log(requestId, "request_done", Map.of(
                "provider", "deepseek",
                "url", safeUrl(url),
                "status", String.valueOf(status),
                "elapsed_ms", String.valueOf(elapsedMs(startedNs)),
                "content_chars", String.valueOf(out.length()),
                "content_sha256", sha256Hex(out.getBytes(StandardCharsets.UTF_8)),
                "content_preview", previewResponse(out)
        ));
        return out;
    }

    private static String configuredRequestUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        return baseUrl.trim();
    }

    private static String extractAssistantContent(JsonNode root) {
        if (root == null) {
            return "";
        }
        JsonNode messageContent = root.path("choices").path(0).path("message").path("content");
        if (messageContent.isTextual() && !messageContent.asText().isBlank()) {
            return messageContent.asText();
        }
        JsonNode textContent = root.path("choices").path(0).path("text");
        if (textContent.isTextual() && !textContent.asText().isBlank()) {
            return textContent.asText();
        }
        return "";
    }

    private static boolean sensitiveLoggingEnabled() {
        String v = System.getenv("SPEC_QC_LOG_SENSITIVE");
        if (v == null) {
            return false;
        }
        String t = v.trim().toLowerCase();
        return t.equals("1") || t.equals("true") || t.equals("yes") || t.equals("y");
    }

    private static String summarizeMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        boolean sensitive = sensitiveLoggingEnabled();
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        int limit = Math.min(6, messages.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> m = messages.get(i);
            String role = m == null ? "" : safeStr(m.get("role"));
            String content = m == null ? "" : safeStr(m.get("content"));
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("{role=").append(role);
            sb.append(", chars=").append(content.length());
            if (sensitive) {
                sb.append(", preview=").append(previewResponse(content));
            }
            sb.append('}');
        }
        if (messages.size() > limit) {
            sb.append(", ... total=").append(messages.size());
        }
        sb.append(']');
        return sb.toString();
    }

    private static String safeStr(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String safeUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("\\s+", "");
    }

    private static String maskSecret(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isBlank()) {
            return "";
        }
        if (s.length() <= 8) {
            return "*".repeat(s.length());
        }
        return s.substring(0, 4) + "*".repeat(Math.max(0, s.length() - 8)) + s.substring(s.length() - 4);
    }

    private static String previewResponse(String s) {
        if (!sensitiveLoggingEnabled()) {
            return "";
        }
        if (s == null) {
            return "";
        }
        String t = s.replace("\r\n", "\n").replace("\n", "\\n");
        int max = 600;
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + "...(truncated,len=" + t.length() + ")";
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static void log(String requestId, String event, Map<String, String> fields) {
        Path logFile = resolveLogFile();
        StringBuilder sb = new StringBuilder();
        sb.append(ISO.format(OffsetDateTime.now()));
        sb.append(" llm");
        sb.append(" request_id=").append(requestId == null ? "" : requestId);
        sb.append(" event=").append(event == null ? "" : event);
        if (fields != null && !fields.isEmpty()) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                if (k.isEmpty()) {
                    continue;
                }
                String v = e.getValue() == null ? "" : e.getValue();
                sb.append(' ').append(k).append('=').append(escapeLogValue(v));
            }
        }
        sb.append('\n');
        try {
            synchronized (LOG_LOCK) {
                Files.createDirectories(logFile.getParent());
                Files.writeString(
                        logFile,
                        sb.toString(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                );
            }
        } catch (Exception ignored) {
        }
    }

    private static String escapeLogValue(String v) {
        String t = v == null ? "" : v;
        t = t.replace("\r\n", "\n").replace("\n", "\\n");
        if (t.contains(" ") || t.contains("=")) {
            t = "\"" + t.replace("\"", "\\\"") + "\"";
        }
        int max = 2000;
        if (t.length() > max) {
            return t.substring(0, max) + "...(truncated,len=" + t.length() + ")";
        }
        return t;
    }

    private static String stackTrace(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private static Path resolveLogFile() {
        String logFile = System.getenv("SPEC_QC_LOG_FILE");
        if (logFile != null && !logFile.trim().isEmpty()) {
            return Path.of(logFile.trim()).toAbsolutePath().normalize();
        }
        String logDir = System.getenv("SPEC_QC_LOG_DIR");
        if (logDir != null && !logDir.trim().isEmpty()) {
            return Path.of(logDir.trim()).toAbsolutePath().normalize().resolve("large-model.log");
        }
        String workDir = System.getenv("SPEC_QC_WORK_DIR");
        if (workDir != null && !workDir.trim().isEmpty()) {
            return Path.of(workDir.trim()).toAbsolutePath().normalize().resolve("logs").resolve("large-model.log");
        }
        Path homeWork = Path.of(System.getProperty("user.home"), "工作", "work", "logs", "large-model.log").toAbsolutePath().normalize();
        if (canUse(homeWork.getParent())) {
            return homeWork;
        }
        Path cwdWork = Path.of("").toAbsolutePath().normalize().resolve("work").resolve("logs").resolve("large-model.log");
        if (canUse(cwdWork.getParent())) {
            return cwdWork;
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "spec_qc_work", "logs", "large-model.log").toAbsolutePath().normalize();
    }

    private static boolean canUse(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = dir.resolve(".probe");
            Files.writeString(probe, "ok", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(probe);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256Hex(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(data);
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String firstHeader(HttpResponse<?> resp, String name) {
        if (resp == null || name == null || name.isBlank()) {
            return "";
        }
        try {
            return resp.headers().firstValue(name).orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, String> extractUsageFields(JsonNode root) {
        if (root == null) {
            return Map.of();
        }
        JsonNode u = root.path("usage");
        if (u == null || u.isMissingNode() || u.isNull() || !u.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        String prompt = u.path("prompt_tokens").isNumber() ? String.valueOf(u.path("prompt_tokens").asLong()) : "";
        String completion = u.path("completion_tokens").isNumber() ? String.valueOf(u.path("completion_tokens").asLong()) : "";
        String total = u.path("total_tokens").isNumber() ? String.valueOf(u.path("total_tokens").asLong()) : "";
        if (!prompt.isBlank()) out.put("usage_prompt_tokens", prompt);
        if (!completion.isBlank()) out.put("usage_completion_tokens", completion);
        if (!total.isBlank()) out.put("usage_total_tokens", total);
        return out;
    }

    private static Map<String, String> extractErrorFields(String respText) {
        if (respText == null || respText.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = MAPPER.readTree(respText);
            JsonNode err = root.path("error");
            if (err == null || err.isMissingNode() || err.isNull() || !err.isObject()) {
                return Map.of();
            }
            Map<String, String> out = new HashMap<>();
            String msg = err.path("message").isTextual() ? err.path("message").asText() : "";
            String type = err.path("type").isTextual() ? err.path("type").asText() : "";
            String code = err.path("code").isTextual() ? err.path("code").asText() : "";
            if (!msg.isBlank()) out.put("message", msg);
            if (!type.isBlank()) out.put("type", type);
            if (!code.isBlank()) out.put("code", code);
            return out;
        } catch (Exception e) {
            return Map.of();
        }
    }
}
