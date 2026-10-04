package com.py_spec_qc.core.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

public final class ConfigLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public AppConfig load() {
        LoadedConfig loaded = loadConfig();
        Map<String, Object> config = loaded.data;
        Map<String, Object> llm = asMap(config.get("llm"));
        Map<String, Object> deepseek = asMap(config.get("deepseek"));
        Map<String, Object> provider = llm.isEmpty() ? deepseek : llm;
        Map<String, Object> scan = asMap(config.get("scan"));
        Map<String, Object> server = asMap(config.get("server"));

        String baseUrl = envOrAny((String) provider.get("base_url"), "LLM_BASE_URL", "DEEPSEEK_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com/v1";
        }

        String model = envOrAny((String) provider.get("model"), "LLM_MODEL", "DEEPSEEK_MODEL");
        if (model == null || model.isBlank()) {
            model = "deepseek-chat";
        }

        String envApiKey = firstNonBlankEnv("LLM_API_KEY", "DEEPSEEK_API_KEY");
        boolean apiKeyFromEnv = envApiKey != null;
        String apiKey = apiKeyFromEnv ? envApiKey : (String) provider.get("api_key");
        if (apiKey != null && (apiKey.equals("YOUR_DEEPSEEK_API_KEY") || apiKey.equals("YOUR_LLM_API_KEY"))) {
            apiKey = null;
        }
        if (apiKey != null && apiKey.isBlank()) {
            apiKey = null;
        }

        Integer timeoutSeconds = parseIntOrNull(envOr(valueToString(provider.get("timeout_seconds")), "LLM_TIMEOUT_SECONDS"));
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 60;
        }

        Integer ruleChunkSize = parseIntOrNull(envOr(valueToString(scan.get("rule_chunk_size")), "SPEC_QC_RULE_CHUNK_SIZE"));
        if (ruleChunkSize == null || ruleChunkSize <= 0) {
            ruleChunkSize = 5;
        }

        String serverHost = envOr((String) server.get("host"), "SPEC_QC_HOST");
        Integer serverPort = parseIntOrNull(envOr(valueToString(server.get("port")), "SPEC_QC_PORT"));

        boolean allowExternal = parseBool(envOr(valueToString(provider.get("allow_external")), "SPEC_QC_ALLOW_EXTERNAL_LLM"), true);
        boolean allowRemote = parseBool(envOr(valueToString(server.get("allow_remote")), "SPEC_QC_ALLOW_REMOTE"), false);
        List<String> allowedIps = parseStringList(envOr(configListString(server.get("allowed_ips")), "SPEC_QC_ALLOWED_IPS"));
        String authToken = trimToNull(envOr((String) server.get("auth_token"), "SPEC_QC_AUTH_TOKEN"));

        Path cwd = Path.of("").toAbsolutePath().normalize();
        List<Path> allowedRoots = parsePathList(envOr(configListString(scan.get("allowed_roots")), "SPEC_QC_ALLOWED_ROOTS"), cwd);

        Path workDir = null;
        String envWork = System.getenv("SPEC_QC_WORK_DIR");
        if (envWork != null && !envWork.isBlank()) {
            Path p = expandHomePath(envWork.trim());
            if (p.isAbsolute()) {
                workDir = p.toAbsolutePath().normalize();
            } else {
                workDir = cwd.resolve(p).toAbsolutePath().normalize();
            }
        } else {
            Object wd = config.get("work_dir");
            if (wd instanceof String s && !s.isBlank()) {
                Path p = expandHomePath(s.trim());
                if (p.isAbsolute()) {
                    workDir = p.toAbsolutePath().normalize();
                } else {
                    workDir = cwd.resolve(p).toAbsolutePath().normalize();
                }
            }
        }

        AppConfig c = new AppConfig();
        c.deepseekBaseUrl = baseUrl;
        c.deepseekModel = model;
        c.deepseekApiKey = apiKey;
        c.deepseekApiKeyFromEnv = apiKeyFromEnv;
        c.deepseekAllowExternal = allowExternal;
        c.deepseekTimeoutSeconds = timeoutSeconds;
        c.scanRuleChunkSize = ruleChunkSize;
        c.scanAllowedRoots = allowedRoots;
        c.workDir = workDir;
        c.configPath = loaded.path == null ? null : loaded.path.toAbsolutePath().normalize();
        c.serverHost = serverHost;
        c.serverPort = serverPort;
        c.serverAllowRemote = allowRemote;
        c.serverAllowedIps = allowedIps;
        c.serverAuthToken = authToken;
        return c;
    }

    private static String envOr(String configValue, String envName) {
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return configValue;
    }

    private static String firstNonBlankEnv(String... names) {
        if (names == null) {
            return null;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            String env = System.getenv(name);
            if (env != null && !env.isBlank()) {
                return env;
            }
        }
        return null;
    }

    private static String envOrAny(String configValue, String... envNames) {
        String env = firstNonBlankEnv(envNames);
        if (env != null) {
            return env;
        }
        return configValue;
    }

    private static boolean parseBool(String s, boolean defaultValue) {
        if (s == null) {
            return defaultValue;
        }
        String t = s.trim().toLowerCase();
        if (t.isEmpty()) {
            return defaultValue;
        }
        return t.equals("1") || t.equals("true") || t.equals("yes") || t.equals("y") || t.equals("on");
    }

    private static String configListString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object o : list) {
                if (o == null) {
                    continue;
                }
                String s = String.valueOf(o).trim();
                if (s.isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(s);
            }
            return sb.toString();
        }
        return valueToString(v);
    }

    private static List<String> parseStringList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String p : raw.split("[,\\s;]+")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<Path> parsePathList(String raw, Path cwd) {
        List<Path> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String p : raw.split("[,\\n;]+")) {
            String t = p.trim();
            if (t.isEmpty()) {
                continue;
            }
            Path path = expandHomePath(t);
            Path abs = path.isAbsolute() ? path : cwd.resolve(path);
            out.add(abs.toAbsolutePath().normalize());
        }
        return out;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String valueToString(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number n) {
            return String.valueOf(n);
        }
        return String.valueOf(v);
    }

    private static final class LoadedConfig {
        private final Path path;
        private final Map<String, Object> data;

        private LoadedConfig(Path path, Map<String, Object> data) {
            this.path = path;
            this.data = data == null ? new LinkedHashMap<>() : data;
        }
    }

    private static LoadedConfig loadConfig() {
        Path p = resolveConfigPath();
        if (p == null) {
            return new LoadedConfig(null, new LinkedHashMap<>());
        }
        String name = p.getFileName().toString().toLowerCase();
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read config file: " + p, e);
        }
        if (name.endsWith(".json")) {
            try {
                return new LoadedConfig(p, MAPPER.readValue(text, new TypeReference<>() {}));
            } catch (IOException e) {
                throw new IllegalArgumentException("Invalid config JSON: " + p, e);
            }
        }
        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            Object loaded = new Yaml().load(text);
            if (loaded == null) {
                return new LoadedConfig(p, new LinkedHashMap<>());
            }
            if (!(loaded instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("Config YAML root must be a mapping/object: " + p);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return new LoadedConfig(p, out);
        }
        throw new IllegalArgumentException("Unsupported config file: " + p);
    }

    private static Path resolveConfigPath() {
        String envConfig = System.getenv("SPEC_QC_CONFIG");
        if (envConfig != null && !envConfig.isBlank()) {
            Path p = expandHomePath(envConfig.trim());
            Path abs = p.isAbsolute() ? p.toAbsolutePath().normalize() : Path.of("").toAbsolutePath().normalize().resolve(p).normalize();
            if (Files.isRegularFile(abs)) {
                return abs;
            }
        }

        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path candidate : candidateConfigsInDir(cwd)) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path execDir = cwd.resolve("executable");
        for (Path candidate : candidateConfigsInDir(execDir)) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        Path jarDir = resolveJarDir();
        if (jarDir != null) {
            for (Path candidate : candidateConfigsInDir(jarDir)) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static Path expandHomePath(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return Path.of("");
        }
        if (s.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (s.startsWith("~" + File.separator)) {
            return Path.of(System.getProperty("user.home")).resolve(s.substring(2));
        }
        if (s.startsWith("~/") || s.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home")).resolve(s.substring(2));
        }
        return Path.of(s);
    }

    private static List<Path> candidateConfigsInDir(Path dir) {
        if (dir == null) {
            return List.of();
        }
        return List.of(
                dir.resolve("config.yaml"),
                dir.resolve("config.yml"),
                dir.resolve("config.example.yaml"),
                dir.resolve("config.example.yml"),
                dir.resolve("config.json")
        );
    }

    private static Path resolveJarDir() {
        try {
            String loc = String.valueOf(ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation());
            String cleaned = loc;
            if (cleaned.startsWith("jar:")) {
                cleaned = cleaned.substring(4);
            }
            int bang = cleaned.indexOf('!');
            if (bang >= 0) {
                cleaned = cleaned.substring(0, bang);
            }
            URI uri = URI.create(cleaned);
            if (!"file".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }
            Path p = Path.of(uri).toAbsolutePath().normalize();
            if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".jar")) {
                return p.getParent();
            }
            if (Files.isDirectory(p)) {
                return p;
            }
        } catch (Exception ignored) {
        }
        try {
            String cp = System.getProperty("java.class.path");
            if (cp != null && !cp.isBlank()) {
                for (String part : cp.split(Pattern.quote(File.pathSeparator))) {
                    String t = part == null ? "" : part.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    Path p = Path.of(t).toAbsolutePath().normalize();
                    if (Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".jar")) {
                        return p.getParent();
                    }
                    if (Files.isDirectory(p)) {
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }
}
