package com.py_spec_qc.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.py_spec_qc.core.JobManager;
import com.py_spec_qc.core.config.AppConfig;
import com.py_spec_qc.core.config.ConfigLoader;
import com.py_spec_qc.core.model.FileProgress;
import com.py_spec_qc.core.model.Issue;
import com.py_spec_qc.core.model.JobStatusResponse;
import com.py_spec_qc.core.model.RootEntry;
import com.py_spec_qc.core.model.ScanRequest;
import com.py_spec_qc.core.xlsx.XlsxIO;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.xmlbeans.XmlCursor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public final class ApiController {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final DateTimeFormatter UPLOAD_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");
    private static final Object LOG_LOCK = new Object();
    private static final Pattern HTML_TABLE = Pattern.compile("(?is)<table>\\s*(.*?)\\s*</table>");
    private static final Pattern HTML_TR = Pattern.compile("(?is)<tr>\\s*(.*?)\\s*</tr>");
    private static final Pattern HTML_TD = Pattern.compile("(?is)<(td|th)>\\s*(.*?)\\s*</\\1>");
    private final JobManager jobManager;
    private final XlsxIO xlsx = new XlsxIO();

    private final Object cacheLock = new Object();
    private final Path workDir;
    private final Path cachePath;

    public ApiController(JobManager jobManager) {
        this.jobManager = jobManager;
        AppConfig config = new ConfigLoader().load();
        Path wd = (config != null && config.workDir != null)
                ? config.workDir
                : Path.of("").toAbsolutePath().normalize().resolve("work");
        this.workDir = wd.toAbsolutePath().normalize();
        this.cachePath = this.workDir.resolve("cache.yaml");
    }

    @GetMapping("/cache")
    public Map<String, String> getCache() {
        Map<String, String> data;
        synchronized (cacheLock) {
            data = loadCache();
        }
        String defaultReq = workDir.resolve("input").toString();
        String defaultOut = workDir.resolve("output").toString();
        String defaultRules = workDir.resolve("quality").toString();
        String req = existingDirOr(data.get("req_dir"), defaultReq);
        String out = existingDirOr(data.get("out_dir"), defaultOut);
        String rules = existingDirOr(data.get("rules_dir"), defaultRules);
        return Map.of(
                "req_dir", req,
                "out_dir", out,
                "rules_dir", rules
        );
    }

    @GetMapping("/llm_config")
    public Map<String, String> getLlmConfig() {
        AppConfig config = new ConfigLoader().load();
        String url = config == null ? "" : safeTrim(config.deepseekBaseUrl);
        String model = config == null ? "" : safeTrim(config.deepseekModel);
        String configPath = (config == null || config.configPath == null) ? "" : config.configPath.toAbsolutePath().normalize().toString();
        String authMode = (config == null || safeTrim(config.deepseekApiKey).isBlank()) ? "missing" : "bearer";
        String timeoutSeconds = String.valueOf((config == null || config.deepseekTimeoutSeconds == null || config.deepseekTimeoutSeconds <= 0) ? 60 : config.deepseekTimeoutSeconds);
        String ruleChunkSize = String.valueOf((config == null || config.scanRuleChunkSize == null || config.scanRuleChunkSize <= 0) ? 5 : config.scanRuleChunkSize);
        return Map.of(
                "url", url,
                "model", model,
                "config_path", configPath,
                "auth_mode", authMode,
                "api_key_masked", maskSecret(config == null ? "" : config.deepseekApiKey),
                "timeout_seconds", timeoutSeconds,
                "rule_chunk_size", ruleChunkSize
        );
    }

    private static String existingDirOr(String candidate, String fallback) {
        String abs = toAbsolutePathString(candidate);
        if (!abs.isBlank()) {
            try {
                if (Files.isDirectory(Path.of(abs))) {
                    return abs;
                }
            } catch (Exception ignored) {
            }
        }
        return toAbsolutePathString(fallback);
    }

    private static String maskSecret(String raw) {
        String s = safeTrim(raw);
        if (s.isBlank()) {
            return "";
        }
        if (s.length() <= 8) {
            return "*".repeat(s.length());
        }
        return s.substring(0, 4) + "*".repeat(Math.max(0, s.length() - 8)) + s.substring(s.length() - 4);
    }

    private static final class UploadDirs {
        private final Path baseWorkDir;
        private final Path reqDir;
        private final Path outDir;
        private final Path rulesDir;
        private final Path uploadsDir;

        private UploadDirs(Path baseWorkDir, Path reqDir, Path outDir, Path rulesDir, Path uploadsDir) {
            this.baseWorkDir = baseWorkDir;
            this.reqDir = reqDir;
            this.outDir = outDir;
            this.rulesDir = rulesDir;
            this.uploadsDir = uploadsDir;
        }
    }

    private UploadDirs resolveUploadDirsOrThrow() throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(workDir);
        try {
            AppConfig config = new ConfigLoader().load();
            if (config != null && config.workDir != null) {
                candidates.add(config.workDir);
            }
            if (config != null && config.configPath != null) {
                Path configDir = config.configPath.toAbsolutePath().normalize().getParent();
                if (configDir != null) {
                    candidates.add(configDir.resolve("work"));
                }
            }
        } catch (Exception ignored) {
        }
        candidates.add(Path.of("").toAbsolutePath().normalize().resolve("work"));
        try {
            candidates.add(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize().resolve("spec-qc-work"));
        } catch (Exception ignored) {
        }

        IOException last = null;
        for (Path raw : candidates) {
            if (raw == null) {
                continue;
            }
            Path base = raw.toAbsolutePath().normalize();
            Path req = base.resolve("input").toAbsolutePath().normalize();
            Path out = base.resolve("output").toAbsolutePath().normalize();
            Path rules = base.resolve("quality").toAbsolutePath().normalize();
            Path uploads = base.resolve("uploads").toAbsolutePath().normalize();
            try {
                Files.createDirectories(req);
                Files.createDirectories(out);
                Files.createDirectories(rules);
                Files.createDirectories(uploads);
                return new UploadDirs(base, req, out, rules, uploads);
            } catch (IOException e) {
                last = new IOException("work_dir=" + base + " err=" + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()), e);
            }
        }
        if (last != null) {
            throw new IOException("创建上传目录失败: " + (last.getMessage() == null ? "" : last.getMessage()), last);
        }
        throw new IOException("创建上传目录失败");
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> startScan(@RequestBody ScanRequest req) {
        String reqDir = req == null ? "" : safeTrim(req.reqDir);
        String outDir = req == null ? "" : safeTrim(req.outDir);
        String rulesDir = req == null ? "" : safeTrim(req.rulesDir);
        String lang = req == null ? "" : safeTrim(req.lang);
        if (reqDir.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "req_dir is required"));
        }
        String absReqDir = toAbsolutePathString(reqDir);
        String absOutDir = outDir.isBlank() ? "" : toAbsolutePathString(outDir);
        String absRulesDir = rulesDir.isBlank() ? "" : toAbsolutePathString(rulesDir);

        AppConfig scanConfig = new ConfigLoader().load();
        boolean rootsConfigured = scanConfig != null && scanConfig.scanAllowedRoots != null && !scanConfig.scanAllowedRoots.isEmpty();
        String violation = firstViolation(scanConfig, "req_dir", absReqDir);
        if (violation == null) {
            violation = firstViolation(scanConfig, "out_dir", absOutDir);
        }
        if (violation == null) {
            violation = firstViolation(scanConfig, "rules_dir", absRulesDir);
        }
        if (violation != null) {
            appendLog("scan_rejected path_violation " + violation);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", violation,
                    "hint", "如需放开请配置 scan.allowed_roots（环境变量 SPEC_QC_ALLOWED_ROOTS），或移除该限制"));
        }
        if (!rootsConfigured && !withinWorkDir(absReqDir)) {
            appendLog("scan_warning outside_work_dir req_dir=" + absReqDir
                    + " work_dir=" + workDir + " hint=可在 config.yaml 配置 scan.allowed_roots 限制扫描范围");
        }

        synchronized (cacheLock) {
            try {
                saveCache(absReqDir, absOutDir, absRulesDir);
            } catch (Exception ignored) {
            }
        }
        String jobId = jobManager.startScan(
                Path.of(absReqDir).toAbsolutePath().normalize(),
                absOutDir.isBlank() ? null : Path.of(absOutDir).toAbsolutePath().normalize(),
                absRulesDir.isBlank() ? null : Path.of(absRulesDir).toAbsolutePath().normalize(),
                lang
        );
        return ResponseEntity.ok(Map.of("job_id", jobId));
    }

    @PostMapping(value = "/scan_upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> startScanUpload(
            @RequestPart("req_files") MultipartFile[] reqFiles,
            @RequestPart(name = "rules_files", required = false) MultipartFile[] rulesFiles,
            @RequestParam(name = "lang", required = false) String lang
    ) {
        if (reqFiles == null || reqFiles.length == 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "req_files is required"));
        }

        UploadDirs dirs;
        try {
            dirs = resolveUploadDirsOrThrow();
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage() == null ? "创建上传目录失败" : e.getMessage()));
        }

        try {
            String uploadId = java.util.UUID.randomUUID().toString().replace("-", "");
            Path tempReqDir = dirs.uploadsDir.resolve(uploadId).resolve("req").toAbsolutePath().normalize();
            Files.createDirectories(tempReqDir);

            List<Path> savedReqFiles = saveUploadedFiles(reqFiles, dirs.reqDir, true);
            for (Path p : savedReqFiles) {
                if (p == null) {
                    continue;
                }
                Path dst = tempReqDir.resolve(p.getFileName().toString()).toAbsolutePath().normalize();
                Files.copy(p, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            if (rulesFiles != null && rulesFiles.length > 0) {
                saveUploadedFiles(rulesFiles, dirs.rulesDir, false);
            }
            String jobId = jobManager.startScan(tempReqDir, dirs.outDir, dirs.rulesDir, safeTrim(lang));
            return ResponseEntity.ok(Map.of("job_id", jobId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
        }
    }

    @GetMapping("/status/{jobId}")
    public JobStatusResponse getStatus(@PathVariable("jobId") String jobId) {
        return jobManager.getJob(jobId);
    }

    @GetMapping("/roots")
    public Map<String, List<RootEntry>> listRoots() {
        List<RootEntry> roots = new ArrayList<>();
        try {
            roots.add(new RootEntry("home", Path.of(System.getProperty("user.home")).toAbsolutePath().normalize().toString()));
        } catch (Exception ignored) {
        }
        try {
            roots.add(new RootEntry("cwd", Path.of("").toAbsolutePath().normalize().toString()));
        } catch (Exception ignored) {
        }
        if (isWindows()) {
            for (char c = 'A'; c <= 'Z'; c++) {
                Path p = Path.of(c + ":\\");
                if (Files.exists(p)) {
                    roots.add(new RootEntry(c + ":\\", c + ":\\"));
                }
            }
        } else {
            roots.add(new RootEntry("/", "/"));
        }
        return Map.of("roots", roots);
    }

    @GetMapping("/fs")
    public Map<String, Object> listDirs(@RequestParam(name = "path", required = false) String path) {
        String p = safeTrim(path);
        if (p.isBlank()) {
            return Map.of("entries", List.of());
        }
        Path dir = Path.of(p).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            return Map.of("error", "not a directory");
        }
        AppConfig fsConfig = new ConfigLoader().load();
        String fsViolation = firstViolation(fsConfig, "path", dir.toString());
        if (fsViolation != null) {
            appendLog("fs_rejected path_violation " + fsViolation);
            return Map.of("error", fsViolation);
        }
        List<Map<String, String>> entries = new ArrayList<>();
        try {
            Files.list(dir)
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(x -> x.getFileName().toString().toLowerCase()))
                    .forEach(child -> entries.add(Map.of(
                            "name", child.getFileName().toString(),
                            "path", child.toAbsolutePath().normalize().toString()
                    )));
        } catch (IOException e) {
            return Map.of("error", "failed to list directory");
        }
        Map<String, Object> out = new HashMap<>();
        out.put("path", dir.toString());
        out.put("entries", entries);
        return out;
    }

    @GetMapping("/pick_dir")
    public Map<String, String> pickDir(@RequestParam(name = "initial", required = false) String initial) {
        if (GraphicsEnvironment.isHeadless()) {
            return Map.of("error", "headless mode: cannot open native directory picker");
        }
        String init = safeTrim(initial);
        final String[] result = new String[] { "", "" };
        Runnable task = () -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setDialogTitle("Choose Folder");
            try {
                if (!init.isBlank()) {
                    Path initPath = Path.of(init).toAbsolutePath().normalize();
                    if (Files.isDirectory(initPath)) {
                        chooser.setCurrentDirectory(initPath.toFile());
                    } else if (initPath.getParent() != null && Files.isDirectory(initPath.getParent())) {
                        chooser.setCurrentDirectory(initPath.getParent().toFile());
                    }
                }
            } catch (Exception ignored) {
            }
            int ret = chooser.showOpenDialog(null);
            if (ret == JFileChooser.APPROVE_OPTION) {
                File f = chooser.getSelectedFile();
                if (f != null) {
                    result[0] = f.toPath().toAbsolutePath().normalize().toString();
                }
            }
        };
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                task.run();
            } else {
                SwingUtilities.invokeAndWait(task);
            }
        } catch (Exception e) {
            result[1] = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        }
        if (!result[1].isBlank()) {
            return Map.of("error", result[1]);
        }
        return Map.of("path", result[0]);
    }

    @GetMapping("/download")
    public ResponseEntity<?> download(@RequestParam("job_id") String jobId, @RequestParam("file_name") String fileName, @RequestParam(name = "lang", required = false) String lang) {
        if (safeTrim(jobId).isBlank() || safeTrim(fileName).isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "job_id and file_name are required"));
        }
        JobStatusResponse job = jobManager.getJob(jobId);
        String outputPath = findOutputPath(job, fileName);
        if (outputPath.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "output not found for file"));
        }
        Path p = Path.of(outputPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "file not found"));
        }
        try {
            ReviewFileData review = loadOrInitReview(job, fileName, p);
            if (review != null && review.issues != null) {
                List<Issue> filtered = review.issues.stream().filter(x -> x != null && !"rejected".equalsIgnoreCase(safeTrim(x.reviewStatus))).toList();
                xlsx.writeIssues(p, fileName, filtered, lang);
            }
        } catch (Exception ignored) {
        }
        byte[] data;
        try {
            data = Files.readAllBytes(p);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentLength(data.length);
        String outFileName = p.getFileName().toString();
        try {
            String encodedName = java.net.URLEncoder.encode(outFileName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName);
        } catch (Exception e) {
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"quality.xlsx\"");
        }
        return new ResponseEntity<>(data, headers, HttpStatus.OK);
    }

    @GetMapping("/issues")
    public ResponseEntity<?> issues(@RequestParam("job_id") String jobId, @RequestParam("file_name") String fileName) {
        if (safeTrim(jobId).isBlank() || safeTrim(fileName).isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "job_id and file_name are required"));
        }
        JobStatusResponse job = jobManager.getJob(jobId);
        List<Issue> inMem = findInMemoryIssues(job, fileName);
        if (inMem != null) {
            return ResponseEntity.ok(Map.of("issues", inMem));
        }
        String outputPath = findOutputPath(job, fileName);
        if (outputPath.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "output not found for file"));
        }
        Path xlsxPath = Path.of(outputPath).toAbsolutePath().normalize();
        Path jsonPath = reviewJsonPath(xlsxPath);
        if (Files.isRegularFile(jsonPath)) {
            try {
                ReviewFileData review = loadReview(jsonPath);
                if (review != null && review.issues != null) {
                    return ResponseEntity.ok(Map.of("issues", review.issues));
                }
            } catch (Exception ignored) {
            }
        }
        try {
            List<Issue> issues = xlsx.readIssues(Path.of(outputPath));
            return ResponseEntity.ok(Map.of("issues", issues));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/review/decision")
    public ResponseEntity<?> reviewDecision(@RequestBody Map<String, String> body) {
        long startedNs = System.nanoTime();
        String jobId = safeTrim(body == null ? null : body.get("job_id"));
        String fileName = safeTrim(body == null ? null : body.get("file_name"));
        String seq = safeTrim(body == null ? null : body.get("seq"));
        String action = safeTrim(body == null ? null : body.get("action")).toLowerCase();
        String reqId = safeTrim(body == null ? null : body.get("req_id"));
        if (jobId.isBlank() || fileName.isBlank() || seq.isBlank() || action.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "job_id/file_name/seq/action are required"));
        }
        if (!action.equals("accept") && !action.equals("reject")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "action must be accept or reject"));
        }
        appendLog("reviewDecision start req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq + " action=" + action);
        JobStatusResponse job = jobManager.getJob(jobId);
        String outputPath = findOutputPath(job, fileName);
        if (outputPath.isBlank()) {
            appendLog("reviewDecision output not found req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq + " action=" + action + " elapsed_ms=" + elapsedMs(startedNs));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "output not found for file"));
        }
        Path xlsxPath = Path.of(outputPath).toAbsolutePath().normalize();
        ReviewFileData review;
        try {
            review = loadOrInitReview(job, fileName, xlsxPath);
        } catch (Exception e) {
            appendLog("reviewDecision load failed req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq + " action=" + action + " elapsed_ms=" + elapsedMs(startedNs) + " err=" + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
        if (review == null || review.issues == null) {
            appendLog("reviewDecision review data not available req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq + " action=" + action + " elapsed_ms=" + elapsedMs(startedNs));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "review data not available"));
        }
        Issue target = null;
        for (Issue it : review.issues) {
            if (it != null && safeTrim(it.seq).equals(seq)) {
                target = it;
                break;
            }
        }
        if (target == null) {
            appendLog("reviewDecision issue not found req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq + " action=" + action + " elapsed_ms=" + elapsedMs(startedNs));
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "issue not found: " + seq));
        }
        target.reviewStatus = action.equals("accept") ? "accepted" : "rejected";
        target.reviewUpdatedAt = nowIsoSeconds();

        if (action.equals("accept")) {
            appendLog("reviewDecision apply start req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq);
            String revised = applyIssueToDoc(review, fileName, target);
            if (revised != null && !revised.isBlank()) {
                review.revisedPath = revised;
            }
            appendLog("reviewDecision apply done req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq + " revised=" + safeTrim(review.revisedPath));
        }

        try {
            saveReview(reviewJsonPath(xlsxPath), review);
        } catch (Exception e) {
            appendLog("reviewDecision save review json failed req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq + " action=" + action + " elapsed_ms=" + elapsedMs(startedNs) + " err=" + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }

        try {
            List<Issue> filtered = review.issues.stream().filter(x -> x != null && !"rejected".equalsIgnoreCase(safeTrim(x.reviewStatus))).toList();
            String lang = safeTrim(body == null ? null : body.get("lang"));
            xlsx.writeIssues(xlsxPath, fileName, filtered, lang);
        } catch (Exception ignored) {
        }

        try {
            updateInMemoryIssues(job, fileName, review.issues);
        } catch (Exception ignored) {
        }

        appendLog("reviewDecision ok req_id=" + reqId + " job_id=" + jobId + " file=" + fileName + " seq=" + seq + " action=" + action + " revised=" + safeTrim(review.revisedPath) + " elapsed_ms=" + elapsedMs(startedNs));
        return ResponseEntity.ok(Map.of(
                "issues", review.issues,
                "revised_path", safeTrim(review.revisedPath)
        ));
    }

    @PostMapping("/client/log")
    public ResponseEntity<?> clientLog(@RequestBody Map<String, String> body) {
        String reqId = safeTrim(body == null ? null : body.get("req_id"));
        String stage = safeTrim(body == null ? null : body.get("stage"));
        String action = safeTrim(body == null ? null : body.get("action"));
        String file = safeTrim(body == null ? null : body.get("file"));
        String seq = safeTrim(body == null ? null : body.get("seq"));
        String ok = safeTrim(body == null ? null : body.get("ok"));
        String err = safeTrim(body == null ? null : body.get("error"));
        if (err.length() > 500) {
            err = err.substring(0, 500);
        }
        appendLog("clientLog req_id=" + reqId + " stage=" + stage + " action=" + action + " file=" + file + " seq=" + seq + " ok=" + ok + " err=" + err);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static final class ReviewFileData {
        @JsonProperty("file_name")
        public String fileName;
        @JsonProperty("source_path")
        public String sourcePath;
        @JsonProperty("revised_path")
        public String revisedPath;
        @JsonProperty("issues")
        public List<Issue> issues = new ArrayList<>();
    }

    private static ReviewFileData loadReview(Path jsonPath) throws IOException {
        ReviewFileData raw = MAPPER.readValue(Files.readString(jsonPath, StandardCharsets.UTF_8), ReviewFileData.class);
        if (raw == null) {
            return null;
        }
        ReviewFileData out = new ReviewFileData();
        out.fileName = safeTrim(raw.fileName);
        out.sourcePath = safeTrim(raw.sourcePath);
        out.revisedPath = safeTrim(raw.revisedPath);
        out.issues = raw.issues == null ? new ArrayList<>() : raw.issues;
        return out;
    }

    private static void saveReview(Path jsonPath, ReviewFileData review) throws IOException {
        Files.createDirectories(jsonPath.getParent());
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "file_name", safeTrim(review == null ? null : review.fileName),
                "source_path", safeTrim(review == null ? null : review.sourcePath),
                "revised_path", safeTrim(review == null ? null : review.revisedPath),
                "issues", (review == null || review.issues == null) ? List.of() : review.issues
        ));
        Files.writeString(jsonPath, json, StandardCharsets.UTF_8);
    }

    private ReviewFileData loadOrInitReview(JobStatusResponse job, String fileName, Path xlsxPath) throws IOException {
        Path jsonPath = reviewJsonPath(xlsxPath);
        if (Files.isRegularFile(jsonPath)) {
            return loadReview(jsonPath);
        }
        ReviewFileData review = new ReviewFileData();
        review.fileName = safeTrim(fileName);
        review.sourcePath = "";
        review.revisedPath = "";
        List<Issue> inMem = findInMemoryIssues(job, fileName);
        if (inMem != null) {
            review.issues = inMem;
        } else if (Files.isRegularFile(xlsxPath)) {
            review.issues = xlsx.readIssues(xlsxPath);
        } else {
            review.issues = new ArrayList<>();
        }
        for (Issue it : review.issues) {
            if (it == null) {
                continue;
            }
            if (safeTrim(it.reviewStatus).isBlank()) {
                it.reviewStatus = "pending";
            }
            if (safeTrim(it.reviewUpdatedAt).isBlank()) {
                it.reviewUpdatedAt = nowIsoSeconds();
            }
        }
        saveReview(jsonPath, review);
        return review;
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

    private String applyIssueToDoc(ReviewFileData review, String fileName, Issue issue) {
        String src = safeTrim(review == null ? null : review.sourcePath);
        if (src.isBlank()) {
            return "";
        }
        Path source = Path.of(src).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || !source.getFileName().toString().toLowerCase().endsWith(".docx")) {
            return "";
        }
        Path reviseDir = workDir.resolve("revise").toAbsolutePath().normalize();
        try {
            Files.createDirectories(reviseDir);
        } catch (IOException e) {
            return "";
        }
        String outName = stem(fileName) + "-revise.docx";
        Path revised = reviseDir.resolve(outName).toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(revised)) {
                Files.copy(source, revised, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            return "";
        }
        try (InputStream in = Files.newInputStream(revised); XWPFDocument doc = new XWPFDocument(in)) {
            XWPFParagraph anchor = findAnchorParagraph(doc, safeTrim(issue.evidenceExcerpt), safeTrim(issue.evidenceParagraph));
            XmlCursor cursor = null;
            if (anchor != null) {
                cursor = anchor.getCTP().newCursor();
                cursor.toEndToken();
            }

            XWPFParagraph p1 = (cursor == null) ? doc.createParagraph() : doc.insertNewParagraph(cursor);
            XWPFRun r1 = p1.createRun();
            r1.setBold(true);
            r1.setText("【建议修订 #" + safeTrim(issue.seq) + "】" + safeTrim(issue.category));

            XWPFParagraph p2 = doc.createParagraph();
            p2.createRun().setText("位置：页号=" + safeTrim(issue.evidencePage) + "；章节=" + safeTrim(issue.evidenceSection));

            XWPFParagraph p3 = doc.createParagraph();
            p3.createRun().setText("问题描述：" + safeTrim(issue.description));

            String para = safeTrim(issue.evidenceParagraph);
            if (!para.isBlank()) {
                XWPFParagraph p4 = doc.createParagraph();
                p4.createRun().setText("内容摘录：" + para);
            }

            String suggestionHtml = safeTrim(issue.suggestionHtml);
            List<List<List<String>>> tables = extractTables(suggestionHtml);
            if (!tables.isEmpty()) {
                XWPFParagraph p5 = doc.createParagraph();
                XWPFRun r5 = p5.createRun();
                r5.setBold(true);
                r5.setText("建议表格：");
                for (List<List<String>> t : tables) {
                    XWPFTable tbl = doc.createTable(Math.max(1, t.size()), Math.max(1, maxCols(t)));
                    fillTable(tbl, t);
                    doc.createParagraph();
                }
            } else {
                String sugText = safeTrim(issue.suggestion);
                if (!sugText.isBlank()) {
                    XWPFParagraph p5 = doc.createParagraph();
                    XWPFRun r5 = p5.createRun();
                    r5.setBold(true);
                    r5.setText("建议：");
                    for (String line : sugText.split("\n")) {
                        XWPFParagraph pp = doc.createParagraph();
                        pp.createRun().setText(line);
                    }
                }
            }
            try (java.io.OutputStream out = Files.newOutputStream(revised)) {
                doc.write(out);
            }
            return revised.toString();
        } catch (Exception e) {
            appendLog("applyIssueToDoc failed file=" + fileName + " seq=" + safeTrim(issue == null ? null : issue.seq) + " err=" + (e.getMessage() == null ? String.valueOf(e) : e.getMessage()));
            return "";
        }
    }

    private static XWPFParagraph findAnchorParagraph(XWPFDocument doc, String excerpt, String paragraph) {
        if (doc == null) {
            return null;
        }
        String ex = safeTrim(excerpt);
        if (!ex.isBlank()) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = safeTrim(p == null ? null : p.getText());
                if (!t.isBlank() && t.contains(ex)) {
                    return p;
                }
            }
        }
        String para = safeTrim(paragraph);
        if (!para.isBlank()) {
            String key = para.length() > 16 ? para.substring(0, 16) : para;
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = safeTrim(p == null ? null : p.getText());
                if (!t.isBlank() && t.contains(key)) {
                    return p;
                }
            }
        }
        List<XWPFParagraph> ps = doc.getParagraphs();
        return ps == null || ps.isEmpty() ? null : ps.get(ps.size() - 1);
    }

    private static List<List<List<String>>> extractTables(String html) {
        String raw = safeTrim(html);
        if (raw.isBlank()) {
            return List.of();
        }
        List<List<List<String>>> out = new ArrayList<>();
        Matcher tm = HTML_TABLE.matcher(raw);
        while (tm.find()) {
            String tableInner = tm.group(1);
            List<List<String>> rows = new ArrayList<>();
            Matcher rm = HTML_TR.matcher(tableInner == null ? "" : tableInner);
            while (rm.find()) {
                String rowInner = rm.group(1);
                List<String> cells = new ArrayList<>();
                Matcher cm = HTML_TD.matcher(rowInner == null ? "" : rowInner);
                while (cm.find()) {
                    String cellInner = cm.group(2);
                    cells.add(htmlToText(cellInner));
                }
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }
            if (!rows.isEmpty()) {
                out.add(rows);
            }
        }
        return out;
    }

    private static int maxCols(List<List<String>> rows) {
        int m = 1;
        for (List<String> r : rows) {
            if (r != null) {
                m = Math.max(m, r.size());
            }
        }
        return m;
    }

    private static void fillTable(XWPFTable tbl, List<List<String>> rows) {
        if (tbl == null) {
            return;
        }
        int rCount = tbl.getNumberOfRows();
        int cols = rCount > 0 ? tbl.getRow(0).getTableCells().size() : 1;
        for (int i = 0; i < rows.size(); i++) {
            List<String> rr = rows.get(i);
            XWPFTableRow tr = (i < rCount) ? tbl.getRow(i) : tbl.createRow();
            for (int j = 0; j < cols; j++) {
                XWPFTableCell cell = tr.getCell(j);
                if (cell == null) {
                    cell = tr.addNewTableCell();
                }
                String v = (rr != null && j < rr.size()) ? rr.get(j) : "";
                cell.removeParagraph(0);
                XWPFParagraph p = cell.addParagraph();
                for (String line : safeTrim(v).split("\n")) {
                    XWPFRun run = p.createRun();
                    run.setText(line);
                    run.addBreak();
                }
            }
        }
    }

    private static String htmlToText(String html) {
        String s = html == null ? "" : html;
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)<[^>]+>", "");
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return s.trim();
    }

    private static String stem(String fileName) {
        String n = safeTrim(fileName);
        int idx = n.lastIndexOf('.');
        return idx < 0 ? n : n.substring(0, idx);
    }

    private static String nowIsoSeconds() {
        return OffsetDateTime.now(ZoneId.systemDefault()).withNano(0).format(ISO_SECONDS);
    }

    private static void updateInMemoryIssues(JobStatusResponse job, String fileName, List<Issue> issues) {
        if (job == null || job.progress == null || job.progress.files == null || safeTrim(fileName).isBlank()) {
            return;
        }
        for (FileProgress fp : job.progress.files) {
            if (fp != null && safeTrim(fp.fileName).equals(fileName)) {
                fp.issues = issues == null ? new ArrayList<>() : issues;
                fp.issueCount = issues == null ? 0 : issues.size();
                return;
            }
        }
    }

    private static void appendLog(String line) {
        String text = nowIsoSeconds() + " " + String.valueOf(line) + "\n";
        Path p = resolveLogPath();
        synchronized (LOG_LOCK) {
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, text, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {
            }
        }
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static Path resolveLogPath() {
        String work = System.getenv("SPEC_QC_WORK_DIR");
        if (work != null && !work.trim().isEmpty()) {
            return Path.of(work.trim()).toAbsolutePath().normalize().resolve("logs").resolve("spec-qc.log");
        }
        try {
            AppConfig config = new ConfigLoader().load();
            if (config != null && config.workDir != null) {
                return config.workDir.toAbsolutePath().normalize().resolve("logs").resolve("spec-qc.log");
            }
        } catch (Exception ignored) {
        }
        return Path.of("").toAbsolutePath().normalize().resolve("work").resolve("logs").resolve("spec-qc.log");
    }

    private static String findOutputPath(JobStatusResponse job, String fileName) {
        if (job == null || job.progress == null || job.progress.files == null) {
            return "";
        }
        for (FileProgress fp : job.progress.files) {
            if (fp == null) {
                continue;
            }
            if (fileName.equals(fp.fileName)) {
                return fp.outputPath == null ? "" : fp.outputPath;
            }
        }
        return "";
    }

    private static List<Issue> findInMemoryIssues(JobStatusResponse job, String fileName) {
        if (job == null || job.progress == null || job.progress.files == null) {
            return null;
        }
        for (FileProgress fp : job.progress.files) {
            if (fp == null) {
                continue;
            }
            if (fileName.equals(fp.fileName) && fp.issues != null) {
                return fp.issues;
            }
        }
        return null;
    }

    private Map<String, String> loadCache() {
        if (!Files.isRegularFile(cachePath)) {
            return Map.of();
        }
        String text;
        try {
            text = Files.readString(cachePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains(":")) {
                continue;
            }
            String[] parts = line.split(":", 2);
            String key = parts[0].trim();
            String value = parts[1].trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(key, value);
        }
        return out;
    }

    private void saveCache(String reqDir, String outDir, String rulesDir) throws IOException {
        Files.createDirectories(cachePath.getParent());
        Path tmp = cachePath.resolveSibling("cache.yaml.tmp");
        String content = ""
                + "req_dir: " + yamlQuote(reqDir) + "\n"
                + "out_dir: " + yamlQuote(outDir) + "\n"
                + "rules_dir: " + yamlQuote(rulesDir) + "\n";
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, cachePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static String yamlQuote(String s) {
        String v = s == null ? "" : s;
        v = v.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + v + "\"";
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }

    private static List<Path> saveUploadedFiles(MultipartFile[] files, Path targetDir, boolean isReq) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        List<Path> saved = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                continue;
            }
            String original = f.getOriginalFilename();
            String baseName = baseNameOnly(original);
            if (baseName.isBlank()) {
                continue;
            }
            String suf = suffixLower(baseName);
            if (isReq) {
                if (!suf.equals(".docx") && !suf.equals(".doc")) {
                    throw new IllegalArgumentException("需求文件格式不支持: " + baseName);
                }
            } else {
                if (!suf.equals(".md") && !suf.equals(".docx") && !suf.equals(".doc")) {
                    throw new IllegalArgumentException("规则文件格式不支持: " + baseName);
                }
                if (suf.equals(".doc")) {
                    throw new IllegalArgumentException("规则文件暂不支持 .doc，请转换为 .docx: " + baseName);
                }
            }

            String datedName = appendTimestampSuffix(baseName);
            Path out = normalizedTarget.resolve(datedName).toAbsolutePath().normalize();
            if (!out.startsWith(normalizedTarget)) {
                throw new IllegalArgumentException("文件名不合法: " + baseName);
            }
            if (Files.exists(out)) {
                String unique = appendTimestampSuffix(stem(baseName) + "_" + randomHex(8) + suffixLower(baseName));
                out = normalizedTarget.resolve(unique).toAbsolutePath().normalize();
            }

            try (InputStream in = f.getInputStream()) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            saved.add(out);
        }
        return saved;
    }

    private static String baseNameOnly(String name) {
        if (name == null) {
            return "";
        }
        String n = name.replace("\\", "/");
        int idx = n.lastIndexOf('/');
        String base = idx >= 0 ? n.substring(idx + 1) : n;
        return base.trim();
    }

    private static String suffixLower(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0) {
            return "";
        }
        return name.substring(idx).toLowerCase();
    }

    private static String appendTimestampSuffix(String baseName) {
        String b = baseNameOnly(baseName);
        String ext = suffixLower(b);
        String s = stem(b);
        String ts = OffsetDateTime.now(ZoneId.systemDefault()).format(UPLOAD_TS);
        return s + "_" + ts + ext;
    }

    private static String randomHex(int len) {
        String s = Long.toHexString(Double.doubleToLongBits(Math.random())).replace("-", "");
        if (s.length() >= len) {
            return s.substring(0, len);
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) {
            sb.append('0');
        }
        return sb.toString();
    }

    private boolean withinWorkDir(String absPath) {
        String t = safeTrim(absPath);
        if (t.isBlank()) {
            return true;
        }
        try {
            return Path.of(t).toAbsolutePath().normalize().startsWith(workDir);
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回 null 表示允许；否则返回违规说明。未配置 allowed_roots 时不做限制（保持兼容）。 */
    private static String firstViolation(AppConfig config, String label, String absPath) {
        if (config == null || config.scanAllowedRoots == null || config.scanAllowedRoots.isEmpty()) {
            return null;
        }
        String t = safeTrim(absPath);
        if (t.isBlank()) {
            return null;
        }
        Path p = Path.of(t).toAbsolutePath().normalize();
        for (Path root : config.scanAllowedRoots) {
            if (root == null) {
                continue;
            }
            if (p.startsWith(root.toAbsolutePath().normalize())) {
                return null;
            }
        }
        return label + " 不在允许的根目录内: " + p + "（可访问根目录: " + allowedRootsText(config.scanAllowedRoots) + "）";
    }

    private static String allowedRootsText(List<Path> roots) {
        StringBuilder sb = new StringBuilder();
        for (Path r : roots) {
            if (r == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(r);
        }
        return sb.toString();
    }

    private static String toAbsolutePathString(String s) {
        String t = safeTrim(s);
        if (t.isBlank()) {
            return "";
        }
        Path p = Path.of(t);
        if (!p.isAbsolute()) {
            p = Path.of("").toAbsolutePath().normalize().resolve(p);
        }
        return p.toAbsolutePath().normalize().toString();
    }
}
